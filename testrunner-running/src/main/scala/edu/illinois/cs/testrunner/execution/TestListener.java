package edu.illinois.cs.testrunner.execution;

import edu.illinois.cs.diaper.StateCapture;
import edu.illinois.cs.diaper.agent.MainAgent;
import org.apache.commons.io.FileUtils;
import org.junit.runner.Description;
import org.junit.runner.notification.Failure;
import org.junit.runner.notification.RunListener;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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

    private String readFile(String path) throws IOException {
        File file = new File(path);
        return FileUtils.readFileToString(file, StandardCharsets.UTF_8);
    }

    @Override
    public void testStarted(Description description) throws Exception {
        //times.put(JUnitTestRunner.fullName(description), System.nanoTime());
        String fullTestName = JUnitTestRunner.fullName(description);
        times.put(fullTestName, System.nanoTime());

        String phase = readFile(MainAgent.tmpfile);
        if(MainAgent.targetTestName.equals(fullTestName)) {
            if(phase.equals("3") || phase.equals("4")) {
                StateCapture sc = new StateCapture(fullTestName);
                System.out.println("MainAgent.targetTestName: " + MainAgent.targetTestName +
                        " fullTestName: " + fullTestName);
                System.out.println("phase: " + phase);
                System.out.println("test listener!!!!!!!!! Capturing the states!!!!!!!!!!!!!");
                sc.capture();
                //System.out.println("sc.dirty: " + sc.dirty);
            }
            else if(phase.equals("5")) {
                StateCapture sc = new StateCapture(fullTestName);
                System.out.println("MainAgent.targetTestName: " + MainAgent.targetTestName +
                        " fullTestName: " + fullTestName);
                System.out.println("phase: " + phase);
                System.out.println("test listener!!!!!!!!! diffing the fields!!!!!!!!!!!!!");
                sc.diffing();
            }
            else if(phase.equals("6")) {
                StateCapture sc = new StateCapture(fullTestName);
                System.out.println("MainAgent.targetTestName: " + MainAgent.targetTestName +
                        " fullTestName: " + fullTestName);
                System.out.println("phase: " + phase);
                System.out.println("test listener!!!!!!!!! reflection on all the fields!!!!!!!!!!!!!");
                sc.reflectionAll();
            }
            /*else if(phase.startsWith("diffField:")) {
                System.out.println("test listener!!!!!!!!! reflection on the states!!!!!!!!!!!!!");
                StateCapture sc = new StateCapture(fullTestName);
                String diffField = phase.replaceFirst("diffField:", "");
                sc.fixing(diffField);
            }*/
            System.out.println("testStarted end!!");
        }

        /*if(MainAgent.targetTestName.equals(fullTestName)
                && (phase.equals("3") || phase.equals("4") || phase.equals("5"))) {
            System.out.println("MainAgent.targetTestName: " + MainAgent.targetTestName +
                    " fullTestName: " + fullTestName);
            System.out.println("phase: " + phase);

            StateCapture sc = new StateCapture(fullTestName);//CaptureFactory.StateCapture(fullTestName);
            System.out.println("test listener!!!!!!!!! Capturing the states!!!!!!!!!!!!!");
            sc.capture();
            //System.out.println("sc.dirty: " + sc.dirty);
        }*/
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

        String phase = readFile(MainAgent.tmpfile);
        if(MainAgent.targetTestName.equals(fullTestName)
                && phase.equals("2")) {
            System.out.println("MainAgent.targetTestName: " + MainAgent.targetTestName +
                    " fullTestName: " + fullTestName);
            System.out.println("phase: " + phase);

            StateCapture sc = new StateCapture(fullTestName);//CaptureFactory.StateCapture(fullTestName);
            System.out.println("test listener!!!!!!!!! Capturing the states!!!!!!!!!!!!!");
            sc.capture();
            //System.out.println("sc.dirty: " + sc.dirty);
        }

        if(phase.startsWith("7 ") && phase.replaceFirst( "7 ", "").equals(fullTestName) ) {
            StateCapture sc = new StateCapture(fullTestName);
            System.out.println("fullTestName at phase 7: " + fullTestName);
            System.out.println("phase: " + phase);
            System.out.println("test listener!!!!!!!!! reflection at phase 7 on all the fields!!!!!!!!!!!!!");
            sc.reflectionAll();
        }
    }
}
