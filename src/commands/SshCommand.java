/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package commands;

import gui.DebugMessage;


/**
 *
 * @author dmcd2356
 */
public class SshCommand {
    private final DebugMessage debugger;            // the debugger for message reporting
    private final CommandLauncher commandLauncher;  // launches in current thread
    private ThreadLauncher.ThreadAction tHandler;
    private ThreadLauncher  threadLauncher;         // launches in seperate thread
    private String  commandExecuted;                // command run from commandLauncher
    private String  commandOutput;                  // stdout & stderr from commandLauncher
    private int     commandRetcode;                 // exitcode from commandLauncher
    private int     cosmosPid;                      // PID of cosmos_init

    /**
     *
     * @param handler - the set of thread action methods
     * @param debug - the debugger class to report messages to
     */
    public SshCommand (ThreadLauncher.ThreadAction handler, DebugMessage debug) {

        // save the thread handler actions
        tHandler = handler;
        debugger = debug;

        // this creates a command launcher on a separate thread
        threadLauncher = new ThreadLauncher(tHandler, debugger);
        commandLauncher = new CommandLauncher();
        cosmosPid = -1;
    }

    public void updateHandler (ThreadLauncher.ThreadAction handler) {
        // update the thread handler actions
        tHandler = handler;
        threadLauncher.stopAll();
        threadLauncher = new ThreadLauncher(tHandler, debugger);
    }
    
    /**
     * this checks whether an instance of QEMU is running.
     * 
     * @return - pid of QEMU session. (-1 if error, 0 if could not read pid value)
     */
    public int qemuCheckIfPresent () {
        // get the list of processes running on the system
        String[] commandlist = { "ps", "-a" };
        int retcode = threadLauncher.start(commandlist, null, "qemuCheckIfPresent");
        return retcode;
    }

    public int cosmosSetKeygen () {
        String[] commandlist = { "ssh-keygen", "-q",
                                 "-f", "\"$HOME/.ssh/known_hosts\"",
                                 "-R", "root@192.168.7.2"
                                };
        commandExecuted = String.join(" ", commandlist);          // the command issued
        commandRetcode  = commandLauncher.start(commandlist, null);
        commandOutput   = commandLauncher.outTextArea.getText();  // the command response
        return commandRetcode;
    }
    
    public int copyLogFiles (String path, String module) {
        if (path.isEmpty() || module.isEmpty()) {
            commandRetcode = -2;
            return commandRetcode;
        }
        
        // yes | scp -o StrictHostKeyChecking=no root@192.168.7.2:/COSMOS/Logs/${1}/* Logs/${1}
        String[] commandlist = { "scp",
                                 "-o", "StrictHostKeyChecking=no",
                                 "root@192.168.7.2:/COSMOS/Logs/" + module + "/*",
                                 "Logs/" + module
                                };
        commandExecuted = String.join(" ", commandlist);          // the command issued
        commandRetcode  = commandLauncher.start(commandlist, path);
        commandOutput   = commandLauncher.outTextArea.getText();  // the command response
        return commandRetcode;
    }
    
    public int copyFileToCosmos (String fname, String srcpath, String dstpath) {
        if (srcpath.isEmpty() || dstpath.isEmpty() || fname.isEmpty()) {
            commandRetcode = -2;
            return commandRetcode;
        }
        
        // yes | scp -o StrictHostKeyChecking=no root@192.168.7.2:/COSMOS/Logs/${1}/* Logs/${1}
        String[] commandlist = { "scp",
                                 "-o", "StrictHostKeyChecking=no",
                                 fname,
                                 "root@192.168.7.2:" + dstpath
                                };
        commandExecuted = String.join(" ", commandlist);          // the command issued
        commandRetcode  = commandLauncher.start(commandlist, srcpath);
        commandOutput   = commandLauncher.outTextArea.getText();  // the command response
        return commandRetcode;
    }
    
    private int sendSshCommandImmediate (String command) {
        commandExecuted = "";
        commandOutput = "";
        if (command == null) {
            commandRetcode = -2;
            return commandRetcode;
        }

        String[] commandlist = { "ssh", "-o", "StrictHostKeyChecking=no",
                                        "-o", "UserKnownHostsFile=/dev/null",
                                        "-o", "LogLevel=quiet",
                                        "root@192.168.7.2", command //, "2>&1"
                                };
        commandExecuted = String.join(" ", commandlist);          // the command issued
        commandRetcode  = commandLauncher.start(commandlist, null);
        commandOutput   = commandLauncher.outTextArea.getText();  // the command response
        return (commandRetcode == 0) ? 0 : -1;
    }
    
    private int sendSshCommandBackground (String jobname, String command) {
        if (jobname == null || command == null)
            return -2;

        int retcode;
        String[] commandlist = { "ssh", "-o", "StrictHostKeyChecking=no",
                                        "-o", "UserKnownHostsFile=/dev/null",
                                        "-o", "LogLevel=quiet",
                                        "root@192.168.7.2", command //, "2>&1"
                                };
        retcode = threadLauncher.start(commandlist, null, jobname);
        debugger.print(DebugMessage.StatusType.Background, "Queue size: " + retcode + ", added job: " + jobname + ", SSH command: " + command);
        return (retcode == 0) ? 0 : -1;
    }

    //============ commands that run on current thread ================

    public int cosmosInitStart (String module, boolean bStrace) {
        threadLauncher.init();
        String straceEnable = "";
        if (bStrace)
            straceEnable = "-s ";
        return sendSshCommandImmediate("cosmos_init " + straceEnable + "-d  -r -c /opt/" + module + ".xml");
    }
    
    /**
     * this checks whether we have a valid ssh connection.
     * (this checks if we can run a simple command using ssh)
     * @return - 0 on success, else we have no ssh connection
     *           255 - typically means no valid ssh key
     */
    public int cosmosCheckSshConnection () {
        return sendSshCommandImmediate("ls");
    }

    public int cosmosMkdir (String dirpath) {
        return sendSshCommandImmediate("mkdir -p " + dirpath);
    }

    public int isProcessRunning () {
        if (cosmosPid < 0)
            return -2;
        return sendSshCommandImmediate("kill -0 " + cosmosPid);
    }
    
    public int setSchedulingModeOff () {
        return sendSshCommandImmediate("echo 0 > /proc/cosmos/scheduling_decisions");
    }

    public int setSchedulingModeOn () {
        return sendSshCommandImmediate("echo 1 > /proc/cosmos/scheduling_decisions");
    }

    public int getCosmosInitPid () {
        return sendSshCommandImmediate ("pidof cosmos_init");
        // TODO: need to handle the case of getting multiple values back
    }
    
    public int enterKDB () {
        return sendSshCommandImmediate ("echo g > /proc/sysrq-trigger");
    }
    
    //============ these methods return conditions of the    ============
    //============ sendSshCommandImmediate & cosmosSetKeygen ============
    
    public String getCommand () {
        return commandExecuted;
    }
    
    public String getResponse () {
        return commandOutput;
    }
    
    public Integer getNumericResponse () {
        int numeric = -1;
        if (commandRetcode == 0 && !commandOutput.isEmpty()) {
            String cosmosval = commandOutput.trim();
            if (!cosmosval.isEmpty())
                numeric = Integer.parseInt(cosmosval);
        }

        return numeric;
    }
    
    //============ commands that run on separate thread ================
    
    public int cosmosInitStop (String type, int pid) {
        return sendSshCommandBackground("cosmosInitStop", "kill -s " + type + " " + pid);
    }
    
    public int getIsProcessRunning () {
        if (cosmosPid < 0)
            return -2;
        return sendSshCommandBackground("getIsProcessRunning", "kill -0 " + cosmosPid);
    }
    
    // gets the list of available module config files loaded
    public int getModuleList () {
        return sendSshCommandBackground ("getModuleList", "ls /opt/*.xml");
    }
    
    public int getRunningProcesses () {
        return sendSshCommandBackground ("getRunningProcesses", "ps | grep -v root | grep -v PID");
    }
    
    public int getCosmosInfo () {
        return sendSshCommandBackground ("getCosmosInfo", "cat /proc/cosmos/info");
    }
    
    public int getCosmosPartitions () {
        return sendSshCommandBackground ("getCosmosPartitions", "cat /proc/cosmos/partitions");
    }
    
    public int getCosmosProcesses () {
        return sendSshCommandBackground ("getCosmosProcesses", "cat /proc/cosmos/processes");
    }
    
    public int getCosmosConnections () {
        return sendSshCommandBackground ("getCosmosConnections", "cat /proc/cosmos/connections");
    }
    
    public int getCosmosPorts () {
        return sendSshCommandBackground ("getCosmosPorts", "cat /proc/cosmos/ports");
    }
    
    public int getCosmosHmfm () {
        return sendSshCommandBackground ("getCosmosHmfm", "cat /proc/cosmos/hmfm_events");
    }
    
    public int getScheduling () {
        return sendSshCommandBackground ("getScheduling", "cat /proc/cosmos/scheduling_decisions");
    }
    
    public void terminateThreadJobs () {
        threadLauncher.stopAll();
    }

    public void setCosmosPid (int pid) {
        cosmosPid = pid;
    }
    
    // TODO: doesn't work - can't run source or "." outside shell
    /*
    public int setupEnvironment (String cosmosPath) {
        commandExecuted = "";
        commandOutput = "";
        if (cosmosPath == null) {
            commandRetcode = -2;
            return commandRetcode;
        }

        String[] commandlist = { "source", cosmosPath + "/environment-setup-i586-cosmos-linux" };
        commandExecuted = String.join(" ", commandlist);          // the command issued
        commandRetcode  = commandLauncher.start(commandlist, null);
        commandOutput   = commandLauncher.outTextArea.getText();  // the command response

        return commandRetcode;
    }
    
    public int compileFiles (String modulepath) {
        commandExecuted = "";
        commandOutput = "";
        if (modulepath == null) {
            commandRetcode = -2;
            return commandRetcode;
        }

        String[] commandlist1 = { "cmake", "." };
        commandExecuted = String.join(" ", commandlist);          // the command issued
        commandRetcode  = commandLauncher.start(commandlist1, modulepath);
        commandOutput   = commandLauncher.outTextArea.getText();  // the command response

        if (commandRetcode == 0) {
            String[] commandlist2 = { "make" };
            commandExecuted = String.join(" ", commandlist);          // the command issued
            commandRetcode  = commandLauncher.start(commandlist2, modulepath);
            commandOutput   = commandLauncher.outTextArea.getText();  // the command response
        }

        return commandRetcode;
    }
    */
}
