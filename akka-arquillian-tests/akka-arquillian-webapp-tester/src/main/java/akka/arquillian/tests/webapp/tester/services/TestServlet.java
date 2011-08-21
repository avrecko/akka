package akka.arquillian.tests.webapp.tester.services;

import akka.arquillian.tests.common.remoteTests.RemoteTestCaseResult;
import akka.arquillian.tests.webapp.tester.tests.RclTests;
import com.google.gson.Gson;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

public class TestServlet extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        RclTests tests = new RclTests();
        RemoteTestCaseResult run = tests.run();
        resp.getWriter().write(new Gson().toJson(run));
    }


}