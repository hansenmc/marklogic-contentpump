package com.marklogic.contentpump;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.mapreduce.InputSplit;
import org.apache.hadoop.mapreduce.TaskAttemptContext;
import org.apache.hadoop.mapreduce.lib.input.FileSplit;

import com.marklogic.mapreduce.CompressionCodec;

/**
 * Reader for CompressedAggXMLInputFormat.
 * @author ali
 *
 * @param <VALUEIN>
 */
public class CompressedAggXMLReader<VALUEIN> extends
    AggregateXMLReader<VALUEIN> {
    public static final Log LOG = LogFactory
        .getLog(CompressedAggXMLReader.class);
    private byte[] buf = new byte[65536];
    private InputStream zipIn;
    private XMLInputFactory factory;
    private ZipEntry currZipEntry;
    private CompressionCodec codec;

    @Override
    public void close() throws IOException {
        super.close();
        //close the zip
        if (zipIn != null) {
            zipIn.close();
        }
    }

    @Override
    public void initialize(InputSplit inSplit, TaskAttemptContext context)
        throws IOException, InterruptedException {
        super.initialize(inSplit, context);
        
        Path file = ((FileSplit) inSplit).getPath();       
        FileSystem fs = file.getFileSystem(context.getConfiguration());
        FSDataInputStream fileIn = fs.open(file);
        factory = XMLInputFactory.newInstance();

        String codecString = conf.get(
            ConfigConstants.CONF_INPUT_COMPRESSION_CODEC,
            CompressionCodec.ZIP.toString());
        if (codecString.equalsIgnoreCase(CompressionCodec.ZIP.toString())) {
            zipIn = new ZipInputStream(fileIn);
            codec = CompressionCodec.ZIP;
            while ((currZipEntry = ((ZipInputStream) zipIn).getNextEntry()) != null) {
                if (currZipEntry.getSize() != 0) {
                    break;
                }
            }
            if (currZipEntry == null) { // no entry in zip
                return;
            }
            ByteArrayOutputStream baos;
            long size = currZipEntry.getSize();
            if (size == -1) {
                baos = new ByteArrayOutputStream();
            } else {
                baos = new ByteArrayOutputStream((int) size);
            }
            int nb;
            while ((nb = zipIn.read(buf, 0, buf.length)) != -1) {
                baos.write(buf, 0, nb);
            }
            try {
                xmlSR = factory
                    .createXMLStreamReader(new ByteArrayInputStream(baos
                        .toByteArray()), encoding);
            } catch (XMLStreamException e) {
                e.printStackTrace();
            }

        } else if (codecString.equalsIgnoreCase(CompressionCodec.GZIP
            .toString())) {
            zipIn = new GZIPInputStream(fileIn);
            codec = CompressionCodec.GZIP;
            try {
                xmlSR = factory.createXMLStreamReader(zipIn, encoding);
            } catch (XMLStreamException e) {
                e.printStackTrace();
            }
        } else {
            throw new UnsupportedOperationException("Unsupported codec: "
                + codec.name());
        }

        initAggConf((FileSplit)inSplit, context);
    }

    private boolean nextRecordInAggregate() throws IOException,
        XMLStreamException, InterruptedException {
        return super.nextKeyValue();
    }

    @Override
    public boolean nextKeyValue() throws IOException, InterruptedException {
        if (zipIn == null) {
            hasNext = false;
            return false;
        }
        try {
            if (codec.equals(CompressionCodec.ZIP)) {
                ZipInputStream zis = (ZipInputStream) zipIn;

                if (xmlSR.hasNext()) {
                    hasNext = nextRecordInAggregate();
                    if (hasNext) {
                        return true;
                    }
                }
                // xmlSR does not hasNext, try next zipEntry if any
                ByteArrayOutputStream baos;
                while ((currZipEntry = zis.getNextEntry()) != null) {
                    if (currZipEntry.getSize() == 0) {
                        continue;
                    }
                    long size = currZipEntry.getSize();
                    if (size == -1) {
                        baos = new ByteArrayOutputStream();
                    } else {
                        baos = new ByteArrayOutputStream((int) size);
                    }
                    int nb;
                    while ((nb = zis.read(buf, 0, buf.length)) != -1) {
                        baos.write(buf, 0, nb);
                    }
                    xmlSR = factory
                        .createXMLStreamReader(new ByteArrayInputStream(baos
                            .toByteArray()), encoding);
                    nameSpaces.clear();
                    baos.close();
                    return nextRecordInAggregate();
                }
                // end of zip
                return false;

            } else if (codec.equals(CompressionCodec.GZIP)) {
                return nextRecordInAggregate();
            }
        } catch (XMLStreamException e1) {
            e1.printStackTrace();
        }
        return true;
    }
    public CompressedAggXMLReader() {
        super();
    }

}
