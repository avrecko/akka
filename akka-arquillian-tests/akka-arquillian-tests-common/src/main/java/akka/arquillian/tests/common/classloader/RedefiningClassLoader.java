package akka.arquillian.tests.common.classloader;

import com.google.common.collect.Maps;
import com.google.common.io.Files;
import com.google.common.io.Resources;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.HashMap;

/**
 * Used for testing, it will define the given classed in itself. Will not properly resolve linked stuff so be careful.
 */
public class RedefiningClassLoader extends ClassLoader {


    private HashMap<String,byte[]> bytecode = Maps.newHashMap();

    public RedefiningClassLoader(Class<?>... templates) {
        super(null);
        for (Class<?> template : templates) {
            URL resource = template.getClassLoader().getResource(template.getName().replace('.', File.separatorChar) + ".class");

            try {
                byte[] bytes = Resources.toByteArray(resource);
                bytecode.put(template.getName().replace('.', File.separatorChar) + ".class", bytes);
                defineClass(template.getName(), bytes, 0, bytes.length);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }


        }

    }


    @Override
    public InputStream getResourceAsStream(String s) {
        if (bytecode.containsKey(s)) {
            byte[] bytes = bytecode.get(s);
            return new ByteArrayInputStream(bytes);
        }
        throw new NullPointerException("Resources not found " + s);
    }
}
