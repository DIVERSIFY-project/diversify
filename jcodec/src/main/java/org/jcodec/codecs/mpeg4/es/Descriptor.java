package org.jcodec.codecs.mpeg4.es;

import java.io.ByteArrayOutputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;

import org.jcodec.common.io.WindowInputStream;

/**
 * This class is part of JCodec ( www.jcodec.org ) This software is distributed
 * under FreeBSD License
 * 
 * @author The JCodec project
 * 
 */
public abstract class Descriptor {
    private int tag;
    private int size;
    private static DescriptorFactory factory = new DescriptorFactory();

    public Descriptor(int tag) {
        this(tag, 0);
    }

    public Descriptor(int tag, int size) {
        this.tag = tag;
        this.size = size;
    }

    public void write(DataOutput out) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream daos = new DataOutputStream(baos);
        doWrite(daos);
        byte[] b = baos.toByteArray();

        out.write(tag);
        out.write(((b.length >> 21) | 0x80) & 0xff);
        out.write(((b.length >> 14) | 0x80) & 0xff);
        out.write(((b.length >> 7) | 0x80) & 0xff);
        out.write(b.length & 0x7F);
        out.write(b);
    }

    protected abstract void doWrite(DataOutput out) throws IOException;

    public static Descriptor read(InputStream input) throws IOException {
        int tag = input.read();
        if (tag == -1)
            return null;
        long size = 0;
        size |= (input.read() << 21) & 0x7f;
        size |= (input.read() << 14) & 0x7f;
        size |= (input.read() << 7) & 0x7f;
        size |= (input.read()) & 0x7f;

        Class<? extends Descriptor> cls = factory.byTag(tag);
        Descriptor descriptor;
        try {
            descriptor = cls.getConstructor(Integer.TYPE, Integer.TYPE).newInstance(tag, (int) size);
        } catch (Exception e) {
            throw new IOException(e);
        }
        descriptor.parse(new WindowInputStream(input, size));
        return descriptor;
    }

    protected abstract void parse(InputStream input) throws IOException;

    public static <T> T find(Descriptor es, Class<T> class1, int tag) {
        if (es.getTag() == tag)
            return (T) es;
        else {
            if (es instanceof NodeDescriptor) {
                for (Descriptor descriptor : ((NodeDescriptor) es).getChildren()) {
                    T res = find(descriptor, class1, tag);
                    if (res != null)
                        return res;
                }
            }
        }
        return null;
    }

    private int getTag() {
        return tag;
    }
}
