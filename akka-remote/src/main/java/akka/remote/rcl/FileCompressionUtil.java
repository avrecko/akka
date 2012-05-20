package akka.remote.rcl;

import com.google.common.io.Files;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.jar.*;
import java.util.jar.Pack200.Packer;

public class FileCompressionUtil {

    public static JarFile createJar(Collection<File> files) {
        try {
            // inefficient
            File tempFile = File.createTempFile("rcl", "classpath");
            FileOutputStream stream = new FileOutputStream(tempFile);
            JarOutputStream out = new JarOutputStream(stream, new Manifest());

            HashSet<String> alreadyAdded = new HashSet<String>();
            for (File file : files) {
                if (file == null || !file.exists() || !file.isDirectory()) continue; // Just in case...
                // for now only support class directories
                recursiveJarDirAdd(file, file, out, alreadyAdded);
            }

            out.close();
            stream.close();
            return new JarFile(tempFile);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    private static void recursiveJarDirAdd(File root, File currentDir, JarOutputStream os, HashSet<String> alreadyAdded) throws IOException {
        int parentPathLength = root.getPath().length() + 1;

        File[] classes = currentDir.listFiles();

        for (File aClass : classes) {
            if (aClass == null || !aClass.exists()) continue;
            if (aClass.isDirectory()) recursiveJarDirAdd(root, aClass, os, alreadyAdded);

            if (aClass.isFile()) {
                // Add archive entry
                String path = aClass.getPath().substring(parentPathLength);
                if (alreadyAdded.contains(path)) continue;
                JarEntry jarAdd = new JarEntry(path);
                jarAdd.setTime(aClass.lastModified());
                os.putNextEntry(jarAdd);
                // Write file to archive
                Files.copy(aClass, os);
                alreadyAdded.add(path);
            }
        }
    }

    public static final File createPack200(JarFile jarFile) {
        // Create the Packer object
        Pack200.Packer packer = Pack200.newPacker();

        // Initialize the state by setting the desired properties
        Map p = packer.properties();
        // take more time choosing codings for better compression
        p.put(Packer.EFFORT, "7");  // default is "5"
        // use largest-possible archive segments (>10% better compression).
        p.put(Packer.SEGMENT_LIMIT, "-1");
        // reorder files for better compression.
        p.put(Packer.KEEP_FILE_ORDER, Packer.FALSE);
        // smear modification times to a single value.
        p.put(Packer.MODIFICATION_TIME, Packer.LATEST);
        // ignore all JAR deflation requests,
        // transmitting a single request to use "store" mode.
        p.put(Packer.DEFLATE_HINT, Packer.FALSE);
        // discard Scala attributes as they brak the compression
        p.put(Packer.UNKNOWN_ATTRIBUTE, Packer.STRIP);
        // discard debug attributes
//        p.put(Packer.CODE_ATTRIBUTE_PFX+"LineNumberTable", Packer.STRIP);
        // throw an error if an attribute is unrecognized
        p.put(Packer.UNKNOWN_ATTRIBUTE, Packer.ERROR);
        try {
            File pack200archive = File.createTempFile("rcl", "pack200classpath");
            FileOutputStream fos = new FileOutputStream(pack200archive);
            // Call the packer
            packer.pack(jarFile, fos);
            jarFile.close();
            fos.close();
            return pack200archive;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static void unpack200(byte[] jarContent, File outputJar) {
        try {
            JarOutputStream jostream = new JarOutputStream(new FileOutputStream(outputJar));
            Pack200.Unpacker unpacker = Pack200.newUnpacker();
            // Call the unpacker
            unpacker.unpack(new ByteArrayInputStream(jarContent), jostream);
            // Must explicitly close the output.
            jostream.close();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

}
