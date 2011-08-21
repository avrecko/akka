package akka.arquillian.tests.webapp.tester.classes;

import java.io.Serializable;

/**
 * Created by IntelliJ IDEA.
 * User: avrecko
 * Date: 8/18/11
 * Time: 10:00 PM
 * To change this template use File | Settings | File Templates.
 */
public class BazCyclic implements Serializable{

    private volatile Baz baz;

    public Baz getBaz() {
        return baz;
    }

    public void setBaz(Baz baz) {
        this.baz = baz;
    }
}
