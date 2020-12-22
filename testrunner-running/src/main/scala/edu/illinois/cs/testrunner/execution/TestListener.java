package edu.illinois.cs.testrunner.execution;

import edu.illinois.cs.diaper.StateCapture;
import edu.illinois.cs.diaper.agent.MainAgent;
import org.junit.runner.Description;
import org.junit.runner.notification.Failure;
import org.junit.runner.notification.RunListener;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class TestListener extends RunListener {
    private final Map<String, Long> times;
    private final Map<String, Double> testRuntimes;
    private final Set<String> ignoredTests;

    public TestListener() {
        testRuntimes = new HashMap<>();
        times = new HashMap<>();
        ignoredTests = new HashSet<>();
    }

    public Set<String> ignored() {
        return ignoredTests;
    }

    public Map<String, Double> runtimes() {
        return testRuntimes;
    }

    @Override
    public void testIgnored(Description description) throws Exception {
        ignoredTests.add(JUnitTestRunner.fullName(description));
    }

    @Override
    public void testStarted(Description description) throws Exception {
        //times.put(JUnitTestRunner.fullName(description), System.nanoTime());
        String fullTestName = JUnitTestRunner.fullName(description);
        times.put(fullTestName, System.nanoTime());

        System.out.println("MainAgent.targetTestName: " + MainAgent.targetTestName +
                " fullTestName: " + fullTestName);
        if(MainAgent.targetTestName.equals(fullTestName)) {
            StateCapture sc = new StateCapture(fullTestName);//CaptureFactory.StateCapture(fullTestName);
            System.out.println("test listener!!!!!!!!! Capturing the states!!!!!!!!!!!!!");
            sc.capture();
            //System.out.println("sc.dirty: " + sc.dirty);
        }
    }

    @Override
    public void testFailure(Failure failure) throws Exception {
        failure.getException().printStackTrace();
    }

    @Override
    public void testAssumptionFailure(Failure failure) {
        failure.getException().printStackTrace();
    }

    @Override
    public void testFinished(Description description) throws Exception {
        final String fullTestName = JUnitTestRunner.fullName(description);

        if (times.containsKey(fullTestName)) {
            final long startTime = times.get(fullTestName);
            testRuntimes.put(fullTestName, (System.nanoTime() - startTime) / 1E9);
        } else {
            System.out.println("Test finished but did not start: " + fullTestName);
        }
    }
}
