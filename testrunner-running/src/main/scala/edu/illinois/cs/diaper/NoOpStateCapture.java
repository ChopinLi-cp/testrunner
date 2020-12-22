package edu.illinois.cs.diaper;

public class NoOpStateCapture implements IStateCapture {

    private String testName;

    public NoOpStateCapture(String entityFQN) {
        this.testName = entityFQN;
    }
    public void runCapture() {
        //System.out.println("wenxi2222222222:");
        //return;
        //System.out.println("Running NoOp State Capture on: " + this.testName);
    }
}
