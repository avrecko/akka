package akka.arquillian.tests.common;

import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;

import java.io.File;
import java.io.FilenameFilter;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * Assuming maven project structure we can find compiled .class files in /classes.
 * <p/>
 * <p>This is used to build our own version of akka.jar from classpath and then deploy it on the webserver via arquillian.
 * <p>Alternatively we can use this in combination with the Shrinkwrap classloader to essentially get 2 akka distribuitons
 * in the same Test.
 */
public class AkkaProjectAssembler {

    public static final int MAX_DRILL_DEPTH = 30;
    private final File root;


    enum AkkaProject {
        AKKA_ARQUILLIAN_TESTS, AKKA_REMOTE, AKKA_ACTOR, AKKA_STM, AKKA_TESTKIT, AKKA_ACTOR_TESTS, AKKA_TYPED_ACTOR;

        public String toProjectDir() {
            return this.name().toLowerCase().replace('_', '-');
        }
    }

    /**
     * Using the <code>clazz</code> to get the root of akka distribuiton in the filesystem.
     *
     * @param clazz
     */
    public AkkaProjectAssembler(Class<?> clazz) {
        Preconditions.checkNotNull(clazz);
        URL url = clazz.getClassLoader().getResource(clazz.getName().replace('.', File.separatorChar) + ".class");
        Preconditions.checkNotNull(url, "Failed to locate the given class pn disk.");
        File file = new File(url.getFile());

        // lets just try and drill down until we find directory with akka-requillian-tests folder and akka-remote
        for (int i = 0; i < MAX_DRILL_DEPTH; i++) {
            if (file == null) break;
            String[] files = file.list(new FilenameFilter() {
                public boolean accept(File dir, String name) {
                    return AkkaProject.AKKA_ARQUILLIAN_TESTS.toProjectDir().equals(name);
                }
            });
            if (files != null && files.length == 1) {
                root = file;
                return;
            }
            file = file.getParentFile();

        }
        throw new NullPointerException("Failed to find akka root location. Maybe you've refacture the top level akka projects?");
    }


    public List<File> assemble(AkkaProject firstProject, AkkaProject... additional) {
        Preconditions.checkNotNull(firstProject);
        ArrayList<File> files = Lists.newArrayList();
        Joiner filePathJoiner = Joiner.on(File.separatorChar);
        File file = new File(root, filePathJoiner.join(firstProject.toProjectDir(), "target", "classes"));
        if (!file.isDirectory()) {
            throw new NullPointerException("Failed to find classes dir for project" + firstProject);
        }
        files.add(file);
        for (AkkaProject project : additional) {
            file = new File(root, filePathJoiner.join(project.toProjectDir(), "target", "classes"));
            if (!file.isDirectory()) {
                throw new NullPointerException("Failed to find classes dir for project" + project);
            }
            files.add(file);
        }

        return files;
    }


}
