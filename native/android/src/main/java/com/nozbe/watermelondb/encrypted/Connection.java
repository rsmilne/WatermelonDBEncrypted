package com.nozbe.watermelondb.encrypted;

import java.util.ArrayList;

public abstract class Connection {
    public static class Connected extends Connection {
        public final WMDatabaseDriver driver;
        
        public Connected(WMDatabaseDriver driver) {
            this.driver = driver;
        }

        @Override
        public ArrayList<Runnable> getQueue() {
            return new ArrayList<>();
        }
    }

    public static class Waiting extends Connection {
        public final ArrayList<Runnable> queueInWaiting;
        
        public Waiting(ArrayList<Runnable> queueInWaiting) {
            this.queueInWaiting = queueInWaiting;
        }

        @Override
        public ArrayList<Runnable> getQueue() {
            return queueInWaiting;
        }
    }

    public static class Disconnected extends Connection {
        @Override
        public ArrayList<Runnable> getQueue() {
            return new ArrayList<>();
        }
    }

    public abstract ArrayList<Runnable> getQueue();
}