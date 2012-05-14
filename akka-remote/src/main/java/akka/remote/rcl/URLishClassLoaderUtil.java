package akka.remote.rcl;

import com.google.common.base.Function;
import com.google.common.base.Predicate;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterators;

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

    /**
     * Copies all found .class files matching the fqnPattern to a temp dir and adding the temp dir to the specified urlish classloader.
     *
     * @param fqnPattern
     * @param urlishClassloader
     */
    public static void copyToPrivateSpace(String fqnPattern, ClassLoader urlishClassloader){

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

    private static  URL[] findUrlsInClass(Class<?> clazz, ClassLoader loader) {
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
                    }   else {
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
