package akka.arquillian.tests.bootstreap.testcase;

import akka.arquillian.tests.common.remoteTests.RemoteTestCaseResult;
import akka.arquillian.tests.common.remoteTests.RemoteTestMethodResult;
import com.google.common.collect.Lists;
import com.google.common.io.Resources;
import com.google.gson.Gson;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.container.test.api.OperateOnDeployment;
import org.jboss.arquillian.junit.Arquillian;
import org.jboss.arquillian.test.api.ArquillianResource;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.Test;
import org.junit.internal.runners.model.MultipleFailureException;
import org.junit.runner.RunWith;

import java.io.File;
import java.net.URL;
import java.util.ArrayList;

/**
 * Created by IntelliJ IDEA.
 * User: avrecko
 * Date: 8/15/11
 * Time: 5:35 PM
 * To change this template use File | Settings | File Templates.
 */
@RunWith(Arquillian.class)
abstract public class AbstractArquillianTest {

    public static final String TEST_SERVER_WAR_RELATIVE_TO_ARQUILLIAN = "akka-arquillian-webapp-dummy/target/akka-arquillian-webapp-dummy-1.0-SNAPSHOT.war";
    public static final String TEST_TESTER_WAR_RELATIVE_TO_ARQUILLIAN = "akka-arquillian-webapp-tester/target/akka-arquillian-webapp-tester-1.0-SNAPSHOT.war";

    @Deployment(name = "A", order = 1, testable = false)
    public static WebArchive createWarA() {
        return createAkkaTestWar(TEST_SERVER_WAR_RELATIVE_TO_ARQUILLIAN);
    }

    @Deployment(name = "B", order = 2, testable = false)
    public static WebArchive createWarB() {
        return createAkkaTestWar(TEST_TESTER_WAR_RELATIVE_TO_ARQUILLIAN);
    }

    private static WebArchive createAkkaTestWar(String warFileRelativeToArquillian) {
        // !!!!!!!!!!!!!!!!!!!!!!!!!!! I M P O R T A N T !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
        // !! if you make changes to the test-webapp make sure to run maven package befor running any tests           !!
        // !!!!!!!!!!!!!!!!!!!!!!!!!!! I M P O R T A N T !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!

        // ok we need to prepare the testing webapp but we must make sure this classloader does not see any test-webapp classes
        // reason for this is that Arquillian is really crappy at doing any classloader isolation at all
        // todo open issue for Arquillian for ClassLoader isolation for embedded containers

        // we are guaranteed by maven project setup that this file will be in root/arquillian/test-case/target/classes/<package>/ dir
        // lets find the test-webapp.war in the filesystem
        int packageDepth = AbstractArquillianTest.class.getName().replaceAll("[^\\.]", "").length() + 1;
        int arquillianDepth = packageDepth + 2;
        URL thisClass = AbstractArquillianTest.class.getClassLoader().getResource(AbstractArquillianTest.class.getName().replace('.', '/') + ".class");
        File thisClassDir = new File(thisClass.getFile()).getParentFile();

        File root = thisClassDir;
        for (int i = 0; i < arquillianDepth; i++) {
            root = root.getParentFile();
        }

        File testWebAppWar = new File(root, warFileRelativeToArquillian);
        WebArchive fromZipFile = ShrinkWrap.createFromZipFile(WebArchive.class, testWebAppWar);
        return fromZipFile;
    }

    @Test
    @OperateOnDeployment("B")
    public void runTests(@ArquillianResource URL url) throws Throwable {
        System.out.println("Deployed on " + url);

        String output = new String(Resources.toByteArray(new URL(url.toString().concat("tests"))));
        RemoteTestCaseResult result = new Gson().fromJson(output, RemoteTestCaseResult.class);
        ArrayList<Throwable> problems = Lists.newArrayList();
        for (RemoteTestMethodResult method : result.methods) {
            if(!method.successful) {
                problems.add(new RuntimeException(method.stackTrace));
            }
        }

        if (!problems.isEmpty()) {
            throw new MultipleFailureException(problems);
        }
    }

}
