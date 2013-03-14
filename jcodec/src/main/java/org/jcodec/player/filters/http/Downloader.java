package org.jcodec.player.filters.http;

import static org.jcodec.player.filters.http.HttpUtils.privilegedExecute;
import static org.jcodec.player.filters.http.MediaInfoParser.parseMediaInfo;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.params.ClientPNames;
import org.apache.http.client.params.CookiePolicy;
import org.apache.http.util.EntityUtils;
import org.jcodec.common.io.Buffer;
import org.jcodec.common.model.Packet;
import org.jcodec.common.model.TapeTimecode;
import org.jcodec.player.filters.MediaInfo;

/**
 * This class is part of JCodec ( www.jcodec.org ) This software is distributed
 * under FreeBSD License
 * 
 * Downloads frames and media info from JCodec streaming server
 * 
 * @author The JCodec project
 * 
 */
public class Downloader {

    private HttpClient client;
    private String url;

    public Downloader(HttpClient client, String url) {
        this.client = client;
        this.url = url;
    }

    public synchronized List<Packet> seekFrame(long pts, byte[] bfr) throws IOException {
        return extractFrame(bfr, new HttpGet(url + "?pts=" + pts));
    }

    public synchronized List<Packet> getFrame(int frameNo, byte[] bfr) throws IOException {
        return extractFrame(bfr, new HttpGet(url + "/" + frameNo));
    }

    public synchronized List<Packet> getFrames(int frameS, int frameE, byte[] bfr) throws IOException {
        return extractFrame(bfr, new HttpGet(url + "/" + frameS + ":" + frameE));
    }

    private List<Packet> extractFrame(byte[] bfr, HttpGet get) throws IOException, ClientProtocolException {
        get.getParams().setParameter(ClientPNames.COOKIE_POLICY, CookiePolicy.IGNORE_COOKIES);
        HttpResponse response = privilegedExecute(client, get);
        HttpEntity entity = response.getEntity();
        if (response.getStatusLine().getStatusCode() != 200) {
            if (entity != null)
                EntityUtils.toByteArray(entity);
            return null;
        }

        Buffer buffer;
        if (bfr == null)
            buffer = new Buffer(EntityUtils.toByteArray(entity));
        else {
            buffer = toBuffer(bfr, entity);
        }

        Header contentType = response.getLastHeader("Content-Type");

        if (contentType != null && contentType.getValue().startsWith("multipart/mixed")) {
            List<Packet> result = parseMultipart(buffer, contentType.getValue());
            return result.size() == 0 ? null : result;
        } else {
            return Arrays.asList(new Packet[] { pkt(toMap(response.getAllHeaders()), buffer) });
        }
    }

    private static List<Packet> parseMultipart(Buffer buffer, String contentType) {
        byte[] sep1 = ("\r\n--" + contentType.split(";")[1].split("=")[1]).getBytes();

        List<Packet> result = new ArrayList<Packet>();
        int to;
        do {
            buffer.read(buffer.search(13, 10) + 2);
            to = buffer.search(sep1);
            if (to != -1) {
                result.add(part(buffer.read(to)));
                buffer.read(2);
            }
        } while (to != -1);
        return result;
    }

    public synchronized MediaInfo downloadMediaInfo() throws IOException {
        HttpGet get = new HttpGet(url);
        get.getParams().setParameter(ClientPNames.COOKIE_POLICY, CookiePolicy.IGNORE_COOKIES);
        HttpResponse response = privilegedExecute(client, get);
        if (response.getStatusLine().getStatusCode() != 200) {
            if (response.getEntity() != null)
                EntityUtils.toByteArray(response.getEntity());
            return null;
        }
        return parseMediaInfo(EntityUtils.toString(response.getEntity()));
    }

    private static TapeTimecode parseTimecode(String timecodeRaw) {
        if (StringUtils.isEmpty(timecodeRaw))
            return null;
        String[] split = StringUtils.split(timecodeRaw, ":");
        if (split.length == 4) {
            return new TapeTimecode(Short.parseShort(split[0]), Byte.parseByte(split[1]), Byte.parseByte(split[2]),
                    Byte.parseByte(split[3]), false);
        } else if (split.length == 3) {
            String[] split1 = StringUtils.split(split[2], ";");
            if (split1.length == 2)
                return new TapeTimecode(Short.parseShort(split[0]), Byte.parseByte(split[1]),
                        Byte.parseByte(split1[0]), Byte.parseByte(split1[1]), true);
        }
        return null;
    }

    private static long longOr0(String val) {
        return val == null ? 0 : Long.parseLong(val);
    }

    private static int intOr0(String val) {
        return val == null ? 0 : Integer.parseInt(val);
    }

    private static boolean boolOrFalse(String val) {
        return val == null ? false : Boolean.parseBoolean(val);
    }

    private static Map<String, String> parseHeaders(Buffer read) {
        HashMap<String, String> result = new HashMap<String, String>();
        for (String line : getLines(read)) {
            String[] split = line.split(": ");
            result.put(split[0], split[1]);
        }
        return result;
    }

    private Buffer toBuffer(byte[] bfr, HttpEntity entity) throws IOException {
        InputStream in = null;
        try {
            in = entity.getContent();
            Buffer tmp = new Buffer(bfr);
            IOUtils.copy(in, tmp.os());
            return tmp.flip();
        } finally {
            in.close();
        }
    }

    private Map<String, String> toMap(Header[] allHeaders) {
        HashMap<String, String> map = new HashMap<String, String>();
        for (Header header : allHeaders) {
            map.put(header.getName(), header.getValue());
        }
        return map;
    }

    private static Packet part(Buffer read) {
        int data = read.search(13, 10, 13, 10);
        Map<String, String> headers = parseHeaders(read.read(data));
        read.read(4);

        return pkt(headers, read);
    }

    private static Packet pkt(Map<String, String> headers, Buffer data) {
        long pts = longOr0(headers.get("JCodec-PTS"));
        long duration = longOr0(headers.get("JCodec-Duration"));
        int frameNo = intOr0(headers.get("JCodec-FrameNo"));
        boolean key = boolOrFalse(headers.get("JCodec-Key"));
        TapeTimecode timecode = parseTimecode(headers.get("JCodec-TapeTimecode"));

        return new Packet(data, pts, 0, duration, frameNo, key, timecode);
    }

    private static List<String> getLines(Buffer buffer) {
        ArrayList<String> lines = new ArrayList<String>();
        int next = buffer.search(13, 10);
        while (next != -1) {
            lines.add(new String(buffer.read(next).toArray()));
            buffer.read(2);
            next = buffer.search(13, 10);
        }

        if (buffer.remaining() > 0)
            lines.add(new String(buffer.toArray()));

        return lines;
    }

    public void close() {
        // client.getConnectionManager().shutdown();
    }
}