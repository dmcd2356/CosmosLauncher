package commands;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import javax.swing.JTextArea;

/**
 * @author dmcd2356
 *
 */
public class CommandLauncher {
    JTextArea outTextArea;
    
    public CommandLauncher () {
        this.outTextArea = null;
    }
    
    /**
     * starts the specified command in the current thread.
     * 
     * @param command - an array of the command and each argument it requires
     * @param workdir - the path from which to launch the command
     * 
     * @return the status of the command (0 = success) 
     */
    public int start(String[] command, String workdir) {
        int retcode = 0;

        // create an output area for stderr and stdout
        this.outTextArea = new JTextArea();

        // build up the command and argument string
        ProcessBuilder builder = new ProcessBuilder(command);
        if (workdir != null) {
            File workingdir = new File(workdir);
            if (workingdir.isDirectory())
                builder.directory(workingdir);
        }
        
        PrintStream standardOut = System.out;
        PrintStream standardErr = System.err;         

        // re-direct stdout and stderr to the text window
        // merge stderr into stdout so both go to the specified text area
        builder.redirectErrorStream(true);
        PrintStream printStream = new PrintStream(new RedirectOutputStream(outTextArea)); 
        System.setOut(printStream);
        System.setErr(printStream);
            
        // run the command
        Process p;
        try {
            p = builder.start();
        } catch (IOException ex) {
            System.err.println("builder failure: " + ex);
            return -1;
        }

        String status;
        BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
        while (p.isAlive()) {
            try {
                while ((status = br.readLine()) != null) {
                    System.out.println(status);
                }
            } catch (IOException ex) {
                System.err.println("BufferedReader failure: " + ex);
                retcode = -1;
                break;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException ex) {
//                System.err.println("Thread.sleep failure: " + ex);
                break;
            }
        }

        if (retcode == 0)
            retcode = p.exitValue();
        p.destroy();

        // restore the stdout and stderr
        System.setOut(standardOut);
        System.setErr(standardErr);

        return retcode;
    }
    
}

