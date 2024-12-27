package com.nozbe.watermelondb.encrypted;

public class Connection {
    public static class Connected {
        public final WMDatabaseDriver driver;

        public Connected(WMDatabaseDriver driver) {
            this.driver = driver;
        }
    }

    public static class Disconnected {
    }

    public ArrayList<Runnable> getQueue() {
        if (this instanceof Connected) {
            return new ArrayList<>();
        } else if (this instanceof Waiting) {
            return ((Waiting) this).queueInWaiting;
        }
        return null;
    }
}