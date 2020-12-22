package edu.illinois.cs.diaper;

import edu.illinois.cs.diaper.agent.MainAgent;

public class CaptureFactory {

    private static boolean shouldCapture = MainAgent.getInstrumentation() == null ? false : true;

    public static IStateCapture getStateCapture(String entityFQN) {
        if (!shouldCapture) {
            return new NoOpStateCapture(entityFQN);
        } else {
            //return StateCapture.instanceFor(entityFQN);
            return FileStateCapture.instanceFor(entityFQN);
        }
    }

    public static IStateCapture getStateCapture(String entityFQN, boolean stats) {
        if (shouldCapture && stats) {
            return StatisticsStateCapture.instanceFor(entityFQN);
        }
        else {
            return CaptureFactory.getStateCapture(entityFQN);
        }
    }

    public static IStateCapture getClassStateCapture(String entityFQN) {
        if (!shouldCapture) {
            return new NoOpStateCapture(entityFQN);
        } else {
            return StateCapture.instanceFor(entityFQN, true);       // This is class level state capture
        }
    }
}
