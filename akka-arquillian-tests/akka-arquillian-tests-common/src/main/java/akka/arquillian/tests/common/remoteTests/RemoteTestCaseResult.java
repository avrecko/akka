package akka.arquillian.tests.common.remoteTests;

/**
 * Created by IntelliJ IDEA.
 * User: avrecko
 * Date: 8/21/11
 * Time: 5:26 PM
 * To change this template use File | Settings | File Templates.
 */
public class RemoteTestCaseResult {
    public final String className;
    public final RemoteTestMethodResult[] methods;

    public RemoteTestCaseResult(String className, RemoteTestMethodResult[] methods) {
        this.className = className;
        this.methods = methods;
    }
}

