/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package commands;

import commands.ThreadLauncher.ThreadInfo;
import gui.DebugMessage;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import javax.swing.JTextArea;

/**
 *
 * @author dmcd2356
 */
public class RunnerThread extends Thread {

    private final DebugMessage debugger;    // the debug message handler
    private final JTextArea stdout;         // text widget to output stdout & stderr to
    private final String[]  command;        // the command to execute
    private final String    workingdir;     // the dir to run the command from
    private final String    jobname;        // name assigned to the job
    private final int       jobid;          // id assigned to job
    private Process p;
    private int exitcode;
    private boolean killProcess;
    private volatile boolean running = false;
    
    public RunnerThread(ThreadLauncher.ThreadInfo threadcmd) {
        this.killProcess = false;
        this.exitcode = -1;
        this.jobid      = threadcmd.jobid;
        this.command    = threadcmd.command;
        this.workingdir = threadcmd.workingdir;
        this.stdout     = threadcmd.stdout;
        this.jobname    = threadcmd.jobname;
        this.debugger   = threadcmd.debugger;
    }

    @Override
    public void run() {
debugger.print(DebugMessage.StatusType.Background, "Begining run: " + jobname);
        try {
            running = true;
            exitcode = issueCommand(this.command);
            running = false;
        } catch (InterruptedException e){
            debugger.print(DebugMessage.StatusType.Event, "--INTERRUPTED--");
            p.destroyForcibly();
            running = false;
        } catch (Exception e) {
            debugger.print(DebugMessage.StatusType.Error, "Failure in issueCommand for: " + this.command[0]);
        }
    }
    	
    /**
     * kills the current job.
     */
    public void killJob() {
        killProcess = true;
    }
    
    /**
     * returns the running status of the thread
     * 
     * @return true if running, false if not
     */
    public boolean running() {
        return running;
    }

    /**
     * returns the status of the job
     * 
     * @return status information about job run.
     */
    public ThreadLauncher.ThreadStatus getStatus() {
        return new ThreadLauncher.ThreadStatus(jobid, jobname, command, exitcode, stdout);
    }
    	
    /**
     * Formats and runs the specified command.
     * 
     * @param command - an array of the command and each argument it requires
     * @return the status of the command (0 = success)
     * @throws Exception 
     */
    private int issueCommand(String[] command) throws InterruptedException, IOException {
        int retcode;

debugger.print(DebugMessage.StatusType.Background, "Executing: " + jobname);
        // build up the command and argument string
        ProcessBuilder builder = new ProcessBuilder(command);
        if (workingdir != null) {
            File workdir = new File(workingdir);
            if (workdir.isDirectory())
                builder.directory(workdir);
        }
    		
        // re-direct stdout and stderr to the text window
        if (this.stdout != null) {
            // merge stderr into stdout so both go to the specified text area
            builder.redirectErrorStream(true);
            PrintStream printStream = new PrintStream(new RedirectOutputStream(this.stdout));
            System.setOut(printStream);
            System.setErr(printStream);
        }
            
debugger.print(DebugMessage.StatusType.Background, "Executing command: " + String.join(" ", command));
        p = builder.start();

        // run the command
        String status;
        BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
        while (p.isAlive()) {
            while ((status = br.readLine()) != null) {
                System.out.println(status);
            }
            if (killProcess) {
debugger.print(DebugMessage.StatusType.Background, "--KILLING--");
                p.getInputStream().close();
                p.getOutputStream().close();	
                p.getErrorStream().close();
                p.destroyForcibly();
            }
            Thread.sleep(100);
        }

        retcode = p.exitValue();
        p.destroy();

        return retcode;
    }
}
