package akka.remote.rcl;

import com.google.common.base.Function;
import com.google.common.base.Predicate;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterators;
import com.google.common.collect.Lists;
import com.google.common.io.Files;
import org.fest.reflect.core.Reflection;

import javax.annotation.Nullable;
import java.io.File;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.*;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.collect.Collections2.filter;
import static com.google.common.collect.Collections2.transform;
import static com.google.common.collect.ImmutableList.copyOf;
import static com.google.common.collect.Lists.newArrayList;
import static java.util.Arrays.asList;

public class URLishClassLoaderUtil {


    public static void initialize(Class<?>... classes) {
        for (Class<?> clazz : classes) {
            try {
                Class.forName(clazz.getName(), true, clazz.getClassLoader());
            } catch (ClassNotFoundException e) {
                throw new AssertionError(e);
            }
        }
    }

    public static void moveToInternalClassPath(String fileNameContains, ClassLoader urlishClassloader) throws Exception {
        File additionalDir =  addAdditionalPath(urlishClassloader);

        ImmutableList<File> cp = filterClassPath(findClassPathFor(urlishClassloader));
        try {
            for (File file : cp) {
                moveToInternalClassPath(fileNameContains, file, file, additionalDir);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static File addAdditionalPath(ClassLoader urlishClassloader) throws Exception{
        // find the add url method
        Class<?> aClass = urlishClassloader.getClass();
        while (aClass != Object.class) {
            for (Method method : aClass.getDeclaredMethods()) {
                Class<?>[] parameterTypes = method.getParameterTypes();
                for (Class<?> type : parameterTypes) {
                    if (type == URL.class) {
                        System.out.println("Adding to " + method.getName() + " of " + urlishClassloader.getClass() + " ins " + urlishClassloader );
                        File tempDir = Files.createTempDir();
                        method.invoke(urlishClassloader, new Object[]{new URL("file://" + tempDir.getCanonicalPath())});
                        return tempDir;
                    }  else  if (type == URL[].class) {
                        System.out.println("Adding to " + method.getName() + " of " + urlishClassloader.getClass() + " ins " + urlishClassloader );
                        File tempDir = Files.createTempDir();
                        method.invoke(urlishClassloader, new Object[]{new URL[]{new URL("file://" + tempDir.getCanonicalPath())}});
                        return tempDir;
                        }
                    }

            }
            aClass = aClass.getSuperclass();
        }
        throw new IllegalArgumentException("Provided classloader of type " + urlishClassloader.getClass() + " is not URLish.");
    }

    public static void moveToInternalClassPath(String fileNameContains, File root, File currentDir, File copyToDir) throws Exception {
        if (currentDir.exists() && currentDir.isDirectory()) {
            File[] files = currentDir.listFiles();
            for (File file : files) {
                if (file.exists()) {
                    if (file.isDirectory()) {
                        moveToInternalClassPath(fileNameContains, root, file, copyToDir);
                    } else {
                        // must be a file
                        if (file.getName().contains(fileNameContains)) {
                            File copyToFullPath = new File(copyToDir, file.getCanonicalPath().substring(root.getCanonicalPath().length() + 1));
                            Files.createParentDirs(copyToFullPath);
                            Files.copy(file, copyToFullPath);
                            if (!file.delete()) {
                                // try a couple of more times
                                int retry = 100;
                                while (!file.delete() && retry-- > 0) {
                                    Thread.sleep(10);
                                    if (retry == 0) {
                                        throw new RuntimeException("Failed to delete file. Cannot guarantee delete from class path.");
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    public static ImmutableList<File> filterClassPath(List<File> files) {
        // get java jre/jdk, ext and library locations we will use to filter the class path
        Properties props = System.getProperties();
        // first get the java home
        String javaJrePath = (String) props.get("java.home");
        // we want to get the root of the java home it should be one level lower then what we get
        final File javaHome = new File(javaJrePath).getParentFile();
        final String javaHomePath = javaHome.getPath();

        // then get the library path
        String librariesProperty = (String) props.get("java.library.path");
        final List<String> libraryPaths = newArrayList(Splitter.on(File.pathSeparatorChar).split(librariesProperty).iterator());

        // get the ext dir
        String extProperty = (String) props.get("java.ext.dirs");
        final List<String> extPaths = newArrayList(Splitter.on(File.pathSeparatorChar).split(extProperty).iterator());

        // now we can just filter this thing
        return copyOf(filter(files, new Predicate<File>() {
            public boolean apply(@Nullable File input) {
                String path = input.getAbsolutePath();

                if (path.startsWith(javaHomePath)) return false;

                for (String libraryPath : libraryPaths) {
                    if (path.startsWith(libraryPath)) return false;
                }
                for (String extPath : extPaths) {
                    if (path.startsWith(extPath)) return false;
                }
                // ignoring sbt stuff is hardcoded
                if (path.contains(".sbt")) return false;
                return true;
            }
        }));
    }

    public static ImmutableList<File> findClassPathFor(ClassLoader cl) {
        checkNotNull(cl, "Cannot find ClassPath for null ClassLoader");
        Class<? extends ClassLoader> clClass = cl.getClass();
        URL[] urlishMethod = findUrlsInClass(clClass, cl);
        checkNotNull(urlishMethod, "Failed to find a method that returs URLs in " + clClass.getName());

        // first filter all non files
        Collection<URL> fileUrls = filter(asList(urlishMethod), new Predicate<URL>() {
            public boolean apply(@Nullable URL input) {
                return "file".equalsIgnoreCase(input.getProtocol());
            }
        });
        // create files from URLs
        return copyOf(transform(fileUrls, new Function<URL, File>() {
            public File apply(@Nullable URL input) {
                return new File(input.getFile());
            }
        }));
    }

    private static URL[] findUrlsInClass(Class<?> clazz, ClassLoader loader) {
        while (clazz != Object.class) {
            for (Method method : clazz.getMethods()) {
                URL[] urls = findUrlsInMethod(method, loader);
                if (urls != null) {
                    return urls;
                }
            }
            clazz = clazz.getSuperclass();
        }
        return null;
    }

    private static URL[] findUrlsInMethod(Method method, ClassLoader loader) {
        Class<?> returnType = method.getReturnType();
        try {
            // hande Array
            if (URL[].class == returnType) {
                method.setAccessible(true);
                return (URL[]) method.invoke(loader);
            }

            // handle Java Collections
            if (Iterable.class.isAssignableFrom(returnType)) {
                method.setAccessible(true);
                Iterable iterable = (Iterable) method.invoke(loader);
                Iterator iterator = iterable.iterator();
                if (iterator.hasNext()) {
                    if (iterator.next() instanceof URL) {
                        return (URL[]) Iterators.toArray(iterable.iterator(), URL.class);
                    } else {
                        return null;
                    }
                }
            }

            // handle Scala Collections
            if (scala.collection.Iterable.class.isAssignableFrom(returnType)) {
                method.setAccessible(true);
                scala.collection.Iterable iterable = (scala.collection.Iterable) method.invoke(loader);
                scala.collection.Iterator iterator = iterable.toIterator();
                if (iterator.hasNext()) {
                    ArrayList<URL> urls = newArrayList();
                    Object next = iterator.next();
                    if (next instanceof URL) {
                        urls.add((URL) next);
                        while (iterator.hasNext()) {
                            urls.add((URL) next);
                        }
                        return urls.toArray(new URL[urls.size()]);
                    } else {
                        return null;
                    }
                }
            }

            return null;
        } catch (Exception e) {
            return null;
        }
    }

}
