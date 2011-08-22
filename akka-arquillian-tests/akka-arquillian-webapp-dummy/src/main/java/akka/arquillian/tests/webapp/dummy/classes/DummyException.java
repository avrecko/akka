package akka.arquillian.tests.webapp.dummy.classes;

/**
 * Created by IntelliJ IDEA.
 * User: avrecko
 * Date: 8/22/11
 * Time: 8:57 AM
 * To change this template use File | Settings | File Templates.
 */
public class DummyException extends RuntimeException{
    public DummyException(String message) {
        super(message);
    }
}
