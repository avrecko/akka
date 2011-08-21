package akka.arquillian.tests.common.remoteTests;

/**
 * Created by IntelliJ IDEA.
 * User: avrecko
 * Date: 8/21/11
 * Time: 5:25 PM
 * To change this template use File | Settings | File Templates.
 */
public class RemoteTestMethodResult {

    public final String name;
    public final boolean successful;
    public final String exceptionClassName;
    public final String message;
    public final String stackTrace;

    public RemoteTestMethodResult(String name, boolean successful) {
        this(name, successful, null, null, null);
    }

    public RemoteTestMethodResult(String name, boolean successful, String exceptionClassName, String message, String stackTrace) {
        this.name = name;
        this.successful = successful;
        this.exceptionClassName = exceptionClassName;
        this.message = message;
        this.stackTrace = stackTrace;
    }
}
