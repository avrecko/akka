package akka.arquillian.tests.common.remoteTests;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Map;

/**
 * Created by IntelliJ IDEA.
 * User: avrecko
 * Date: 8/21/11
 * Time: 5:25 PM
 * To change this template use File | Settings | File Templates.
 */
public class RemoteTestCase {


    private volatile boolean run = false;

    private volatile RemoteTestCaseResult results;

    public synchronized RemoteTestCaseResult run() {
        if (run) return results;
        Map<Method, Throwable> runResults = Maps.newHashMap();
        Method[] methods = this.getClass().getMethods();
        for (Method method : methods) {
            if (method.getName().startsWith("test")) {
                try {
                    method.invoke(this);
                    runResults.put(method, null);
                } catch (Throwable e) {
                    runResults.put(method, e);
                }
            }
        }
        run = true;
        results = convertToResults(runResults);
        return results;
    }

    private RemoteTestCaseResult convertToResults(Map<Method, Throwable> results) {
        ArrayList<RemoteTestMethodResult> methodResults = Lists.newArrayList();
        for (Map.Entry<Method, Throwable> entry : results.entrySet()) {
            Method key = entry.getKey();
            Throwable value = entry.getValue();
            if (value == null) {
                methodResults.add(new RemoteTestMethodResult(key.getName(), true));
            }  else {
                methodResults.add(new RemoteTestMethodResult(key.getName(),false, value.getClass().getName(),value.getMessage(), getStackTrace(value)));
            }
        }

        return new RemoteTestCaseResult(this.getClass().getName(), methodResults.toArray(new RemoteTestMethodResult[methodResults.size()]));
    }



    public static String getStackTrace(Throwable aThrowable) {
        final Writer result = new StringWriter();
        final PrintWriter printWriter = new PrintWriter(result);
        aThrowable.printStackTrace(printWriter);
        return result.toString();
    }
}
