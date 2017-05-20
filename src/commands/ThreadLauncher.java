/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package commands;

import gui.DebugMessage;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.LinkedList;
import java.util.Queue;
import javax.swing.JTextArea;
import javax.swing.Timer;

/**
 *
 * @author dmcd2356
 */
public class ThreadLauncher {

    public interface ThreadAction {
        public void jobstarted (ThreadStatus info);
        public void jobfinished (ThreadStatus info);
        public void allcompleted (ThreadStatus info);
    }

    // the data passed to the thread
    public static class ThreadInfo {
        public final int        jobid;        // the job id
        public final String     jobname;      // type of job being run
        public final String     workingdir;   // the dir to execute the command from
        public final String[]   command;      // the command to execute
        public final JTextArea  stdout;       // text widget to output stdout & stderr to
        public final DebugMessage debugger;   // the debugger for message reporting
        
        ThreadInfo (String jobname, String dir, String[] command, JTextArea stdout, DebugMessage dbg) {
            this.jobid      = ++jobnumber;
            this.jobname    = jobname;
            this.workingdir = dir;
            this.command    = command;
            this.stdout     = stdout;
            this.debugger   = dbg;
        }
    }

    // the data returned from the thread
    public static class ThreadStatus {
        public final int        jobid;        // the job id
        public final String     jobname;      // type of job run
        public final String     fullcommand;  // the fully expanded command
        public final JTextArea  outTextArea;  // output from command
        public final int        exitcode;     // the command exit code
        public int              qsize;        // the current queue size
        
        // this is used when starting a command
        ThreadStatus (ThreadInfo command, int qsize) {
            this.jobid       = command.jobid;
            this.jobname     = command.jobname;
            this.fullcommand = String.join(" ", command.command);
            this.outTextArea = command.stdout;
            this.exitcode    = -1;
            this.qsize       = qsize;
        }
        
        // this is used when a command completes
        ThreadStatus (int jobid, String jobname, String[] command, int exitcode, JTextArea stdout) {
            this.jobid       = jobid;
            this.jobname     = jobname;
            this.fullcommand = String.join(" ", command);
            this.outTextArea = stdout;
            this.exitcode    = exitcode;
            this.qsize       = 0;
        }
        
        public void setQSize (int qsize) {
            this.qsize = qsize;
        }
    }

    // Queue for commands to execute in separate thread
    private static int   jobnumber;
    private RunnerThread runner;
    private ThreadAction action;
    private JTextArea    outTextArea;
    private final DebugMessage debugger;            // the debugger for message reporting
    private final Timer timer;
    private final Queue<ThreadInfo> commandQueue = new LinkedList<>();
    private final int count = 0;
    
    public ThreadLauncher (ThreadAction action, DebugMessage dbg) {
        this.jobnumber = 0;
        this.runner = null;
        this.action = action;
        this.outTextArea = null;
        this.debugger = dbg;

        // start the timer to run every 100 msec with no delay
        this.timer = new Timer(100, new TimerListener());
        this.timer.setInitialDelay(0);
        
        this.init();
    }
    
    public void init (ThreadAction action) {
        // this allows the user to change the handler for the thread launcher
        if (action != null)
            this.action = action;
        
        debugger.print(DebugMessage.StatusType.Background, "Init Launcher: (queue = " + commandQueue.size() + ")");
        this.runner = null;
        this.commandQueue.clear();
    }
    
    public final void init () {
        init(null);
    }

    /**
     * starts the specified command in a separate thread. 
     * 
     * @param command - an array of the command and each argument it requires
     * @param workdir - the path from which to launch the command
     * @param jobname - name to identify the job being run
     * 
     * @return the number of entries currently in the queue
     */
    public int start (String[] command, String workdir, String jobname) {
        
        // create an output area and add the command to the queue
        this.outTextArea = new JTextArea();
        ThreadInfo commandInfo = new ThreadInfo(jobname, workdir, command, this.outTextArea, this.debugger);
        this.commandQueue.add(commandInfo);

        // make sure the timer is running
        this.timer.start(); 
        debugger.print(DebugMessage.StatusType.Background, "timer started");
        
        // return the current queue size
        return commandQueue.size();
    }

    public void stopAll () {
        ThreadInfo threadInfo ;
        debugger.print(DebugMessage.StatusType.Background, "Stop Launcher: (queue = " + commandQueue.size() + ")");
        while (!commandQueue.isEmpty()) {
            threadInfo = commandQueue.remove();
            debugger.print(DebugMessage.StatusType.Background, "  removed job: " + threadInfo.jobname);
        }
        this.runner.killJob();
        this.runner.interrupt();
        this.commandQueue.clear();
    }
    
    class TimerListener implements ActionListener {
        
        private ThreadInfo threadInfo;
        
        @Override
        public void actionPerformed(ActionEvent event) {

            if (true) {
//            if (count++ % 10 == 0) {
                if (runner == null)
                    debugger.print(DebugMessage.StatusType.Background, "runner: null, qsize " + commandQueue.size());
                else if (runner.running())
                    debugger.print(DebugMessage.StatusType.Background, "runner: running, qsize " + commandQueue.size());
                else
                    debugger.print(DebugMessage.StatusType.Background, "runner: inactive, qsize " + commandQueue.size());
            }
            
            // no thread is running, start the first command (if there is any)
            if (runner == null) {
        	if (!commandQueue.isEmpty()) {
                    // get the first job to run
                    threadInfo = commandQueue.remove();
                    if (action != null) {
                        ThreadStatus status = new ThreadStatus(threadInfo, commandQueue.size());
                        action.jobstarted(status);
                    }
                    
                    // start the job
                    runner = new RunnerThread(threadInfo);
                    Thread t = new Thread(runner);
                    t.start();
        	}
                return;
            }

            if (runner.running() /*|| exitcode < 0*/ ) {
                // command still running, so exit and wait some more
                return;
            }
            
            // command has completed - get the status
            ThreadStatus resultStatus = runner.getStatus();
            resultStatus.setQSize(commandQueue.size());
            
            // if there are more commands in the queue, start the next command
            if (!commandQueue.isEmpty()) {
debugger.print(DebugMessage.StatusType.Background, "command completed: qsize " + commandQueue.size());
                // get the next job to run
                threadInfo = commandQueue.remove();
debugger.print(DebugMessage.StatusType.Background, "starting new thread: " + threadInfo.jobname);
                
                if (action != null) {
                    ThreadStatus status = new ThreadStatus(threadInfo, commandQueue.size());
                    action.jobfinished(resultStatus);
                    action.jobstarted(status);
                }

                // start the next job
                runner = new RunnerThread(threadInfo);
                Thread t = new Thread(runner);
                t.start();
            }
            else {
debugger.print(DebugMessage.StatusType.Background, "command completed: qsize empty");
                // all commands have completed or there was an error on the last job...
                // stop timer
                Timer t = (Timer) event.getSource();
                t.stop();
                debugger.print(DebugMessage.StatusType.Background, "timer stopped");
                runner = null;

                // perform thread complete action
                if (action != null && threadInfo != null) {
                    action.jobfinished(resultStatus);
                    action.allcompleted(resultStatus);
                }
            }
        }
    }
}
