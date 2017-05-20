/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package gui;

import commands.SshCommand;
import commands.ThreadLauncher;
import gui.LauncherFrame.SshCommandHandler;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import org.apache.commons.io.FileUtils;

/**
 *
 * @author dmcd2356
 */
public class LauncherFrame extends javax.swing.JFrame {

    private static final String newLine = System.getProperty("line.separator");

    /**
     * Creates new form LauncherFrame
     */
    public LauncherFrame() {
        initComponents();
        cosmosState = CosmosState.initialization;

        // create an elapsed timer
        elapsedTimer = new ElapsedTimer(elapsedTimeLabel);

        // create debugger window
        debug = new DebugMessage(debugTextPane, elapsedTimer);
        
        // create the command interface to cosmos
        cosmosInterface = new SshCommand(new SshCommandHandler(), debug);
        
        // make sure QEMU is up and running before continuing
        startCosmosButton.setEnabled(false);
        stopCosmosButton.setEnabled(false);
        copyLogfilesButton.setEnabled(false);
        commandExecuteButton.setEnabled(false);
        commandTerminateButton.setEnabled(false);

        // initialize the module selection to none (we will have to query for list)
        moduleComboBox.removeAllItems();

        // indicate cosmos_init is not yet running        
        bCosmosRunning = false;
        
        // start the timer and begin check to see if QEMU is running
        loopCount = 0;
        elapsedTimer.start();
        cosmosInterface.qemuCheckIfPresent();

        // init the tables
        procTableInfo = new TableInfo(procTable, "Type Status UID PID Name", debug);
        hmfmTableInfo = new TableInfo(hmfmTable, "Timestamp Process Error Name", debug);
//        portTableInfo = new TableInfo(portTable, "SrcPort SrcID SRCPartition SrcStatus SrcCount SrcTimestamp DstPort DstID DstPartition DstStatus DstCount DstTimestamp");
        cosmosPartList = new ArrayList<>();
        cosmosProcList = new ArrayList<>();
    }

    /**
     * This performs the actions to take upon completion of the thread command.
     */
    public class SshCommandHandler implements ThreadLauncher.ThreadAction {

        @Override
        public void allcompleted(ThreadLauncher.ThreadStatus info) {
            // copy output data to window
            System.out.flush();
            System.err.flush();
            debug.print(DebugMessage.StatusType.JobCompleted, "Job queue empty");
        }

        @Override
        public void jobstarted(ThreadLauncher.ThreadStatus info) {
            debug.print(DebugMessage.StatusType.JobStarted, "Job: " + info.jobname + " started (qsize = " + info.qsize + ")");
        }

        @Override
        public void jobfinished(ThreadLauncher.ThreadStatus info) {
            // get the stdout / stderr message
            BufferedReader reader;
            String line;
            debug.print(DebugMessage.StatusType.JobCompleted, "Job: " + info.jobname + " completed with exitcode = " + info.exitcode + " (qsize = " + info.qsize + ")");
            String message = info.outTextArea.getText();
            if (!message.isEmpty())
                debug.print(DebugMessage.StatusType.Results, message);
            
            switch (info.jobname) {
                // the format of the output is: (PID) (TTY) (TIME) (CMD)
                // where PID is the pid of the process and CMD is the running command
                // search for an entry where group 1 is the pid and group 2 is the QEMU command
                // (this assumes there will only be 1 instance of QEMU running and
                // that the QEMU command gets truncated to the 15 chars: qemu-system-i38
                case "qemuCheckIfPresent":
                    if (info.exitcode < 0) {
                        // command failure - check status again
                        statusLabel.setText("QEMU not detected. Please start in order to continue.");
                        cosmosInterface.qemuCheckIfPresent();
                        break;
                    }

                    // check for QEMU in 'message'
                    String search = "^(\\d*)[ \t]*.*(qemu-system-i38).*";
                    Pattern pattern = Pattern.compile(search, Pattern.MULTILINE);
                    Matcher match = pattern.matcher(message);
                    if (!match.find()) {
                        // QEMU is not running - continue checking
                        statusLabel.setText("QEMU not detected. Please start in order to continue.");
                        cosmosInterface.qemuCheckIfPresent();
                        break;
                    }

                    // else, match was found
                    String entry = match.group(1);
                    infoLabel1.setText(entry);
                    statusLabel.setText("");

                    // enable the raw command controls
                    copyLogfilesButton.setEnabled(true);
                    commandExecuteButton.setEnabled(true);
                    commandTerminateButton.setEnabled(true);

                    // check on ssh connection (and try to fix if possible)
                    int retcode = runSshImmediateCommand("cosmosCheckSshConnection");
                    if (retcode > 1) {
                        if (retcode == 255) {
                            // this error usually indicates there is a problem with the ssh key.
                            // try generating a new one.
                            retcode = runSshImmediateCommand("cosmosSetKeygen");
                            if (retcode != 0) {
                                statusLabel.setText("SSH key generation failure: " + retcode);
                                break;
                            }
                            // now re-try SSH command
                            retcode = runSshImmediateCommand("cosmosCheckSshConnection");
                        }
                        if (retcode > 1) {
                            statusLabel.setText("SSH connection failure: " + retcode);
                        }
                    }
                    else {
                        // everything looks good for communicating with COSMOS.
                        // this will request the list of available modules to run
                        cosmosInterface.getCosmosInfo(); // run this first
                        cosmosInterface.getModuleList();
                                
                        // let's see if cosmos_init is already running
                        retcode = runSshImmediateCommand("getCosmosInitPid");
                        if (retcode == 0) {
                            cosmosPid = cosmosInterface.getNumericResponse();
                            cosmosInterface.setCosmosPid(cosmosPid);
                            if (cosmosPid > 0) {
                                // yes, enable the stop button
                                stopCosmosButton.setEnabled(true);
                                        
                                // this will start the process of getting regular status updates
                                cosmosInterface.getIsProcessRunning();
                            }
                        }
                    }
                    break;

                case "getIsProcessRunning":
                    // this command is used to determine whether the cosmos_init is running
                    // and if so to begin querying status for the gui and if not to
                    // stop the timer.
                    if (info.exitcode == 0) {
                        // set flag indicating cosmos_init is running
                        bCosmosRunning = true;
                        
                        // check if we are attempting to stop the process.
                        // if so, continue the attempt
                        if (cosmosState == CosmosState.stopping && 
                            stopCounter > 0 && cosmosPid > 0) {
                            if (stopDelay > 0) {
                                --stopDelay;
                                cosmosInterface.getIsProcessRunning();
                                break;
                            }
                            stopDelay = 10;
                            --stopCounter;
                            String type;
                            switch (stopCounter) {
                                default :
                                case 3: type = "SIGUSR1";   break;
                                case 2: type = "SIGTERM";   break;
                                case 1: type = "SIGKILL";   break;
                            }
                            cosmosInterface.cosmosInitStop (type, cosmosPid);
                            break;
                        }

                        // load up a round of queries
                        // I think I only need these the 1st time thru
                        if (loopCount == 0) {
                            cosmosInterface.getCosmosPorts();
                            cosmosInterface.getCosmosInfo();
                        }

                        ++loopCount;
                        cosmosInterface.getRunningProcesses();
                        cosmosInterface.getCosmosPartitions();
                        cosmosInterface.getCosmosProcesses();
                        cosmosInterface.getCosmosConnections();
                        cosmosInterface.getCosmosHmfm();
                        // must be the last to keep the queries going
                        cosmosInterface.getIsProcessRunning();
                    }
                    else {
                        // clear flag indicating cosmos_init is not running
                        bCosmosRunning = false;
                        
                        // terminate any pending commands
//                        cosmosInterface.terminateThreadJobs();
                    
                        // get one last update of the running status and errors
                        cosmosInterface.getCosmosInfo();
                        cosmosInterface.getCosmosHmfm();
                        
                        if (cosmosState == CosmosState.stopping)
                            debug.print(DebugMessage.StatusType.Info, "Module stopped.");
                        else
                            debug.print(DebugMessage.StatusType.Info, "Module terminated.");
                        elapsedTimer.stop();
                    }
                    break;

                case "cosmosInitStop":
                    // I don't think there's anything to do here
                    break;
                    
                case "getModuleList":
                    getModuleList_response (info.exitcode, message);
                    break;
                    
                case "getRunningProcesses":
                    getRunningProcesses_response (info.exitcode, message);
                    break;
                    
                case "getCosmosInfo":
                    getCosmosInfo_response (info.exitcode, message);
                    break;
                    
                case "getCosmosHmfm":
                    getCosmosHmfm_response (info.exitcode, message);
                    break;
                    
                case "getCosmosPartitions":
                    getCosmosPartitions_response (info.exitcode, message);
                    break;
                    
                case "getCosmosProcesses":
                    getCosmosProcesses_response (info.exitcode, message);
                    break;
                    
                case "getCosmosConnections":
                    getCosmosConnections_response (info.exitcode, message);
                    break;
                    
                case "getCosmosPorts":
                    getCosmosPorts_response (info.exitcode, message);
                    break;
                    
                case "getScheduling":
                    getScheduling_response (info.exitcode, message);
                    break;

                default:
                    break;
            }
        }
    }

    /**
     * Handles the /proc/cosmos/info response.
     * 
     * @param exitcode - 0 if successful
     * @param message  - the multi-line message contents:
     *      Version: 1.0
     *      PID:     0
     *      Module:  
     *      Started: 0
     *      Ended:   0
     *      Current: 5050530926491
     */
    public void getCosmosInfo_response (int exitcode, String message) {
        if (exitcode != 0)
            return;

        String pid = "", module = "", started = "", ended = "";
        BufferedReader reader = new BufferedReader(new StringReader(message));
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] words = line.trim().split("[ ]+");
                if (words.length == 0)
                    break;
                String entry = "---";
                if (words.length >= 2)
                    entry = words[1];
                switch (words[0]) {
                    case "PID:":     pid     = entry;  break;
                    case "Module:":  module  = entry;  break;
                    case "Started:": started = entry;  break;
                    case "Ended:":   ended   = entry;  break;
                    default:
                        break;
                }
            }
            // trim off the .xml from the module name
            if (module.contains(".xml"))
                module = module.substring(0, module.indexOf(".xml"));
            infoLabel2.setText(pid);
            infoLabel3.setText(module);
            if (ended.isEmpty() || ended.equals("0")) {
                infoLabel4.setText("Started: " + getTimestampSec(started));
                cosmosPid = Integer.parseInt(pid);
            }
            else
                infoLabel4.setText("STOPPED");
        } catch (IOException ex) {
            // ignore for now
        }
    }
    
    // DEAD partid:2 pid:2091 cause:0 addr:0 tstamp:648114533870226 name:RecvPartition
    // EXIT partid:2 pid:2091 cause:0 addr:0 tstamp:648114534210805 name:RecvPartition
    public void getCosmosHmfm_response (int exitcode, String message) {
        if (exitcode != 0)
            return;

        // init current list entries to indicate no updates to any of them
        hmfmTableInfo.initListUpdates();
        BufferedReader reader = new BufferedReader(new StringReader(message));
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] words = line.trim().split("[ ]+");
                if (words.length < 5)
                    break; // invalid entry

                String type   = words[0]; // DEAD, EXIT, ...
                String partid = findTagInArray(words, "partid");
                String procid = findTagInArray(words, "pid");
                String cause  = findTagInArray(words, "cause");
                String addr   = findTagInArray(words, "addr");
                String tstamp = findTagInArray(words, "tstamp");
                String name   = findTagInArray(words, "name");

                // ignore any DEAD reports for "sh" or "cat"
                if (type.equals("DEAD") && (name.equals("sh") || name.equals("cat")))
                    continue;

                // show data based on the type of error that occurred
                switch (type) {
                    case "DEAD":
                        // TODO: convert exitcode into appropriate value
                        String exit_string = "";
                        switch (cause) {
                            case "1":  exit_string="SIGHUP" ; break;
                            case "2":  exit_string="SIGINT" ; break;
                            case "3":  exit_string="SIGQUIT"; break;
                            case "4":  exit_string="SIGILL" ; break;
                            case "5":  exit_string="SIGTRAP"; break;
                            case "6":  exit_string="SIGABRT"; break;
                            case "7":  exit_string="SIGBUS" ; break;
                            case "8":  exit_string="SIGFPE" ; break;
                            case "9":  exit_string="SIGKILL"; break;
                            case "10": exit_string="SIGUSR1"; break;
                            case "11": exit_string="SIGSEGV"; break;
                            case "12": exit_string="SIGUSR2"; break;
                            case "13": exit_string="SIGPIPE"; break;
                            case "14": exit_string="SIGALRM"; break;
                            case "15": exit_string="SIGTERM"; break;
                            default:
                                break;
                        }
                        if (exit_string.isEmpty())
                            cause = "exit code: " + cause;
                        else
                            cause = "exit code: " + exit_string;
                        break;

                    case "HMFM":
                        name = "@ 0x" + addr + " : " + name;
                        cause = "HMFM error: " + cause;
                        break;

                    case "KERN":
                        name = "@ 0x" + addr + " : " + name;
                        switch (cause) {
                            case "0": cause = "Kernel WARNING";  break;
                            case "1": cause = "Kernel BUG";      break;
                            case "2": cause = "Kernel PANIC";    break;
                            default:
                                break;
                        }
                        break;

                    case "EXIT":
                        name = "";
                        cause = "Partition set to IDLE";
                        break;

                    default:
                        break;
                }

                // eliminate path in name of process
                int offset = name.lastIndexOf('/');
                if (offset > 0)
                    name = name.substring(offset+1);

                // save entry in the table
                hmfmTableInfo.newListEntry();
                hmfmTableInfo.setListEntry("Timestamp", getTimestampSec(tstamp));
                hmfmTableInfo.setListEntry("Process"  , partid + ":" + procid);
                hmfmTableInfo.setListEntry("Error"    , cause);
                hmfmTableInfo.setListEntry("Name"     , name);
                hmfmTableInfo.addListEntry();
            }
        } catch (IOException ex) {
            // ignore for now
        }

        // update the gui
        hmfmTableInfo.tableUpdate();
    }

    public void getRunningProcesses_response (int exitcode, String message) {
        if (exitcode != 0)
            return;

        // init current list entries to indicate no updates to any of them
        procTableInfo.initListUpdates();
        BufferedReader reader = new BufferedReader(new StringReader(message));
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] words = line.trim().split("[ ]+");
                if (words.length < 5)
                    break; // invalid entry
                String pid    = words[0];
                String uid    = words[1];
                String type   = ""; // can't get the type from here
                String status = words[3];
                String name   = words[4];
                switch (status.charAt(0)) {
                    case 'X': status = "DEAD";            break;
                    case 'Z': status = "DEAD (zombie)";   break;
                    case 'T': status = "DORMANT";         break;
                    case 't': status = "DORMANT (trace)"; break;
                    case 'D': status = "WAIT (uninter)";  break;
                    case 'S': status = "WAIT (inter)";    break;
                    case 'R': status = "READY";           break;
                    default:
                        break;
                }

                // eliminate path in name of process
                int offset = name.lastIndexOf('/');
                if (offset > 0)
                    name = name.substring(offset+1);

                // search for UID entry in partition table
                CosmosPartitionInfo entry = null;
                for (int ix = 0; ix < cosmosPartList.size(); ix++) {
                    // if partition UID found, set flag to indicate valid
                    if (uid.equals(cosmosPartList.get(ix).partid)) {
                        entry = cosmosPartList.get(ix);
                        entry.bValid = true; // only change this value
                        cosmosPartList.set(ix, entry);
                        
                        // also, update some of the values from here
                        type = entry.type;
                    
                        // also check if pid matches the master for the partition
                        // if so, change name to Master Process
                        if (pid.equals(cosmosPartList.get(ix).master)) {
                            name = "Master Process";
                        }
                    }
                }

                procTableInfo.newListEntry();
                procTableInfo.setListEntry("Type"  , type);
                procTableInfo.setListEntry("Status", status);
                procTableInfo.setListEntry("UID"   , uid);
                procTableInfo.setListEntry("PID"   , pid);
                procTableInfo.setListEntry("Name"  , name);

                // if the PID entry already exists, simply update the list entry
                // otherwise, add it to the list.
                int ix = procTableInfo.findListEntry ("PID");
                if (ix < 0)
                    procTableInfo.addListEntry();
                else
                    procTableInfo.replaceListEntry(ix);

                // if entry not found, add new entry to partition table
                if (entry == null) {
                    entry = new CosmosPartitionInfo();
                    entry.partid  = uid;
                    entry.type    = "";
                    entry.master  = "";
                    entry.bValid  = true;
                    entry.bActive = false; // init to false - this is set by getCosmosPartitions
                    cosmosPartList.add(entry);
                }
            }

            // any entry in the list that wasn't updated, mark it as dead
            for (int ix = 0; ix < procTableInfo.list.size(); ix++) {
                if (procTableInfo.isListUpdated(ix) == false) {
                    procTableInfo.saveListEntry(ix);
                    procTableInfo.setListEntry("Status", "DEAD (gone)");
                    procTableInfo.replaceListEntry(ix);
                }
            }
        } catch (IOException ex) {
            // ignore for now
        }

        // update the gui
        procTableInfo.tableUpdate();
    }

    public void getModuleList_response (int exitcode, String message) {
        if (exitcode != 0)
            return;

        if (exitcode != 0) {
            statusLabel.setText("No modules loaded. Xfer desired module to COSMOS.");
            return;
        }

        moduleComboBox.removeAllItems();
        BufferedReader reader = new BufferedReader(new StringReader(message));
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                int offset = line.lastIndexOf("/");
                if (offset >= 0)
                    line = line.substring(offset+1);
                offset = line.lastIndexOf(".");
                if (offset < 0)
                    continue;
                if (line.substring(offset).equals(".xml")) {
                    moduleComboBox.addItem(line.substring(0,offset));
                }
            }
        } catch (IOException ex) {
            // ignore for now
        }

        // if we have at least 1 entry, set selection to last entry
        // and enable the start button
        if (moduleComboBox.getItemCount() > 0) {
            startCosmosButton.setEnabled(true);
            stopCosmosButton.setEnabled(true);

            // if we are running the initialization, try to select
            // the last module run.
            // if we have just run the copy module, let's try to set
            // the selection to the module just copied.
            // otherwise, just use the last entry in the list.
            String item = null;
            String lastModule = infoLabel3.getText();
            if (cosmosState == CosmosState.initialization && !lastModule.isEmpty()) {
                moduleComboBox.setSelectedItem(lastModule);
                item = (String)moduleComboBox.getSelectedItem();
                if (item != null && !item.equals(lastModule))
                    item = null;
            }
            else if (cosmosState == CosmosState.copymodule &&
                     moduleName != null && !moduleName.isEmpty()) {
                moduleComboBox.setSelectedItem(moduleName);
                item = (String)moduleComboBox.getSelectedItem();
                if (item != null && !item.equals(moduleName))
                    item = null;
            }
            if (item == null)
                moduleComboBox.setSelectedIndex(moduleComboBox.getItemCount()-1);
        }

        // if we were initializing, we are done now
        if (cosmosState == CosmosState.initialization)
            cosmosState = CosmosState.idle;
    }

    // id:1: uid:1000 type:2 master:2153 refcnt:2
    public void getCosmosPartitions_response (int exitcode, String message) {
        if (exitcode != 0)
            return;

        // reset the table flags to indicate none found yet
        for (int ix = 0; ix < cosmosPartList.size(); ix++) {
            CosmosPartitionInfo entry = cosmosPartList.get(ix);
            entry.bActive = false;
            cosmosPartList.set(ix, entry);
        }
        
        // init current list entries to indicate no updates to any of them
        BufferedReader reader = new BufferedReader(new StringReader(message));
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] words = line.trim().split("[ ]+");
                if (words.length < 5)
                    break; // invalid entry

                // extract info into table
                CosmosPartitionInfo entry = new CosmosPartitionInfo();
                entry.type   = findTagInArray(words, "type");
                entry.partid = findTagInArray(words, "uid");
                entry.master = findTagInArray(words, "master");
                entry.bValid = false;
                entry.bActive = true;
                
                // skip the system partition (id = 0)
                if (!entry.partid.equals("0")) {
                    // convert the partition type to something intelligible
                    switch (entry.type) {
                        case "2": entry.type = "ARINC"; break;
                        case "1": entry.type = "POSIX"; break;
                        default:  entry.type = "";      break;
                    }
                
                    // if entry already listed, just update the values
                    boolean bFound = false;
                    for (int ix = 0; ix < cosmosPartList.size(); ix++) {
                        if (entry.partid.equals(cosmosPartList.get(ix).partid)) {
                            entry.bValid = cosmosPartList.get(ix).bValid; // don't change bValid
                            cosmosPartList.set(ix, entry);
                            bFound = true;
                        }
                    }
                    // else, add the new entry
                    if (!bFound)
                        cosmosPartList.add(entry);
                }
            }
        } catch (IOException ex) {
            // ignore for now
        }
    }

    // uid:1 pid:2157 partid:2 state:READY susp:0 prior:36 per:N name:<RECEIVER>
    public void getCosmosProcesses_response (int exitcode, String message) {
        if (exitcode != 0)
            return;

        // init current list entries to indicate no updates to any of them
        BufferedReader reader = new BufferedReader(new StringReader(message));
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] words = line.trim().split("[ ]+");
                if (words.length < 5)
                    break; // invalid entry

                // extract info into table
                CosmosProcessInfo entry = new CosmosProcessInfo();
                entry.name    = findTagInArray(words, "name");
                entry.pid     = findTagInArray(words, "pid");
                entry.state   = findTagInArray(words, "state");
                entry.suspend = findTagInArray(words, "susp");
                cosmosProcList.add(entry);
            }
        } catch (IOException ex) {
            // ignore for now
        }
    }

    // QUE s:- cnt:6 time:648559113468292 uid:1000 id:0 <PORT1> *TO* s:E cnt:6 time:648559113468292 uid:1001 id:0 <PORT2>
    public void getCosmosConnections_response (int exitcode, String message) {
//        if (exitcode != 0)
//            return;
    }

    // QUE dir:S conn:1 q:0 s:- cnt:4 time:648557112806054 uid:1000 id:0 <PORT1>
    public void getCosmosPorts_response (int exitcode, String message) {
//        if (exitcode != 0)
//            return;
    }

    public void getScheduling_response (int exitcode, String message) {
//        if (exitcode != 0)
//            return;
    }

    private String findTagInArray (String[] array, String tag) {
        if (tag == null || tag.isEmpty() || array == null || array.length == 0)
            return "";
        for (String word : array) {
            if (word.startsWith(tag + ":")) {
                return word.substring(tag.length()+1);
            }
        }
        return "";
    }

    // convert timestamp to sec with msec resolution instead of nsec
    private String getTimestampSec (String tstamp) {
        String tmsec, tsec;
        int tlength = tstamp.length();
        if (tlength > 9) {
            tsec  = tstamp.substring(0, tlength-9);
            tmsec = tstamp.substring(tlength-9, tlength-6);
        }
        else {
            tsec  = "0";
            tmsec = tstamp.substring(0,3);
        }

        return tsec + "." + tmsec;
    }
    
    private void showSshImmediateCommand (String command, int exitcode) {
        debug.print(DebugMessage.StatusType.JobStarted, "Job: " + command + " = " + cosmosInterface.getCommand());
        debug.print(DebugMessage.StatusType.JobCompleted, "Job: " + command + " completed with exitcode = " + exitcode);
        debug.print(DebugMessage.StatusType.Results, cosmosInterface.getResponse());
    }

    private int runSshImmediateCommand (String command) {
        int exitcode = -1;
        switch (command) {
            case "cosmosSetKeygen":           exitcode = cosmosInterface.cosmosSetKeygen();           break;
            case "cosmosCheckSshConnection":  exitcode = cosmosInterface.cosmosCheckSshConnection();  break;
            case "isProcessRunning":          exitcode = cosmosInterface.isProcessRunning();          break;
            case "setSchedulingModeOff":      exitcode = cosmosInterface.setSchedulingModeOff();      break;
            case "setSchedulingModeOn":       exitcode = cosmosInterface.setSchedulingModeOn();       break;
            case "getCosmosInitPid":          exitcode = cosmosInterface.getCosmosInitPid();          break;
            case "enterKDB":                  exitcode = cosmosInterface.enterKDB();                  break;
            default:
                debug.print(DebugMessage.StatusType.Error, "runSshImmediateCommand: Invalid command: " + command);
                return exitcode;
        }
        
        // now show the command progress in the debug window
        showSshImmediateCommand(command, exitcode);
        return exitcode;
    }

    public int copyModuleFiles (String modulepath, String modulename) {
        if (modulepath == null || modulepath.isEmpty()) {
            debug.print(DebugMessage.StatusType.Error, "copyModuleFiles: null module path");
            return -1;
        }
        
        if (modulename == null || modulename.isEmpty()) {
            debug.print(DebugMessage.StatusType.Error, "copyModuleFiles: null module name");
            return -1;
        }
        
        // get the name of the script to read
        File script = new File(modulepath + "/copyfiles.sh");
        if(!script.isFile()) {
            debug.print(DebugMessage.StatusType.Error, "copyModuleFiles: copyfiles.sh not found at: " + modulepath);
            return -1;
        }

        // first we do the xml file (because it is always a single file and always
        // named modulename.xml contained in the xml subdir from the modulepath.
        // we don't use the command from the script file because it usually contains
        // a '*' char which doesn't work here.
        String srcfile;
        String cosmospath = "/opt";
        int exitcode = cosmosInterface.cosmosMkdir (cosmospath);
        showSshImmediateCommand("cosmosMkdir", exitcode);
        if (exitcode == 0) {
            cosmosInterface.copyFileToCosmos ("xml/" + modulename + ".xml", modulepath, cosmospath);
            showSshImmediateCommand("copyFileToCosmos", exitcode);
        }
        
        // read the file path/module/copyfiles.sh
        try {
            BufferedReader in = new BufferedReader(new FileReader(script));
            String line = in.readLine();
            while (line != null) {
                // find each line that starts with "scp " and create a command list from it
                if (line.startsWith("scp ")) {
                    String[] commandlist = line.trim().split("[ ]+");
                    if (commandlist.length == 3) {
                        // the 2nd entry is the file to copy (usually a relative path from modulepath)
                        // the 3rd entry is the path in COSMOS to copy the file to
                        srcfile = commandlist[1];
                        cosmospath = commandlist[2];

                        // strip off the root@ field to get just the path to create
                        int offset = cosmospath.indexOf('/');
                        if (offset >= 0)
                            cosmospath = cosmospath.substring(offset);
                        exitcode = cosmosInterface.cosmosMkdir (cosmospath);
                        showSshImmediateCommand("cosmosMkdir", exitcode);
                        if (exitcode == 0) {
                            // remove the relative path indication (if any)
                            if (srcfile.startsWith("./"))
                                srcfile = srcfile.substring(2);
                            // (check for xml file and ignore it, since we have already done this one)
                            if (!srcfile.contains(".xml")) {
                                cosmosInterface.copyFileToCosmos (srcfile, modulepath, cosmospath);
                                showSshImmediateCommand("copyFileToCosmos", exitcode);
                            }
                        }
                    }
                }
                line = in.readLine();
            }
            in.close();
        } catch (FileNotFoundException ex) {
            // should have already been caught above
            debug.print(DebugMessage.StatusType.Error, ex + " (copyModuleFiles)");
        } catch (IOException ex) {
            debug.print(DebugMessage.StatusType.Error, ex + " (copyModuleFiles)");
        }

        return 0;
    }
    
    /**
     * creates a text pane containing text and adds it to a tabbed frame
     * 
     * @param tabbedPane - the tabbed frame to add the text panel to
     * @param tabName  - title of the text panel tab
     * @param filename - name of file containing the text to add to the text panel
     */
    private void addTextTab (javax.swing.JTabbedPane tabbedPane, String tabName, String filename) {

        File textfile = new File(filename);
        if (!textfile.isFile()) {
            debug.print(DebugMessage.StatusType.Error, "File is not valid: " + filename);
            return;
        }
        if (tabName == null || tabName.isEmpty()) {
            debug.print(DebugMessage.StatusType.Error, "Invalid name for tab: " + tabName);
            return;
        }

        BufferedReader in = null;
        try {
            // create a text area and place it in a scrollable panel
            javax.swing.JTextArea fileText = new javax.swing.JTextArea(0, 0);
            fileText.setLineWrap(true);
            fileText.setWrapStyleWord(true);
            javax.swing.JScrollPane fileScroll = new javax.swing.JScrollPane(fileText);
                
            // place the scroll pane in the tabbed pane
            tabbedPane.addTab(tabName, fileScroll);

            // now read the file contents into the text area
            in = new BufferedReader(new FileReader(textfile));
            String line = in.readLine();
            while(line != null){
                fileText.append(line + "\n");
                line = in.readLine();
            }
            fileText.setCaretPosition(0); // set position to start of file
        } catch (FileNotFoundException ex) {
            debug.print(DebugMessage.StatusType.Error, ex + "<FILE_NOT_FOUND> - accessing " + tabName + " file");
        } catch (IOException ex) {
            debug.print(DebugMessage.StatusType.Error, ex + "<IO_EXCEPTION>");
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ex) {
                    debug.print(DebugMessage.StatusType.Error, ex + "<IO_EXCEPTION>");
                }
            }
        }
    }

    /**
     * removes a tab that begins with the specified name.
     * 
     * @param tabbedPane - the tabbed pane to remove an entry from
     * @param name - the name (or begining of the name) of the tab
     * 
     * @return 1 if tab removed, 0 if not
     */
    private int removeTextTab (javax.swing.JTabbedPane tabbedPane, String name) {
        int count = tabbedPane.getTabCount();
        for (int ix = 0; ix < count; ix++) {
            String tabname = tabbedPane.getTitleAt(ix);
            if (tabname.startsWith(name)) {
                tabbedPane.removeTabAt(ix);
                debug.print(DebugMessage.StatusType.Info, "Removed tab: " + tabname);
                return 1;
            }
        }

        return 0;
    }
    
    /**
     * This method is called from within the constructor to initialize the form.
     * WARNING: Do NOT modify this code. The content of this method is always
     * regenerated by the Form Editor.
     */
    @SuppressWarnings("unchecked")
    // <editor-fold defaultstate="collapsed" desc="Generated Code">//GEN-BEGIN:initComponents
    private void initComponents() {

        modulePathChooser = new javax.swing.JFileChooser();
        logPathChooser = new javax.swing.JFileChooser();
        runPanel = new javax.swing.JPanel();
        infoPanel = new javax.swing.JPanel();
        jLabel2 = new javax.swing.JLabel();
        infoLabel1 = new javax.swing.JLabel();
        jLabel3 = new javax.swing.JLabel();
        infoLabel2 = new javax.swing.JLabel();
        jLabel4 = new javax.swing.JLabel();
        infoLabel3 = new javax.swing.JLabel();
        infoLabel4 = new javax.swing.JLabel();
        jLabel5 = new javax.swing.JLabel();
        startCosmosButton = new javax.swing.JButton();
        stopCosmosButton = new javax.swing.JButton();
        moduleComboBox = new javax.swing.JComboBox<>();
        elapsedTimeLabel = new javax.swing.JLabel();
        statusLabel = new javax.swing.JLabel();
        procPanel = new javax.swing.JPanel();
        procScrollPane = new javax.swing.JScrollPane();
        procTable = new javax.swing.JTable();
        controlTabbedPane = new javax.swing.JTabbedPane();
        setupPanel = new javax.swing.JPanel();
        straceCheckBox = new javax.swing.JCheckBox();
        copyLogfilesButton = new javax.swing.JButton();
        modpathButton = new javax.swing.JButton();
        logpathTextField = new javax.swing.JTextField();
        copyModuleButton = new javax.swing.JButton();
        modpathTextField = new javax.swing.JTextField();
        logpathButton = new javax.swing.JButton();
        debugPanel = new javax.swing.JPanel();
        jLabel1 = new javax.swing.JLabel();
        commandExecuteButton = new javax.swing.JButton();
        commandComboBox = new javax.swing.JComboBox<>();
        pidTextField = new javax.swing.JTextField();
        commandTerminateButton = new javax.swing.JButton();
        termTypeComboBox = new javax.swing.JComboBox<>();
        hmfmScrollPane = new javax.swing.JScrollPane();
        hmfmTable = new javax.swing.JTable();
        logTabbedPane = new javax.swing.JTabbedPane();
        debugScrollPane = new javax.swing.JScrollPane();
        debugTextPane = new javax.swing.JTextPane();
        portScrollPane = new javax.swing.JScrollPane();
        portTable = new javax.swing.JTable();

        modulePathChooser.setFileSelectionMode(javax.swing.JFileChooser.DIRECTORIES_ONLY);

        logPathChooser.setFileSelectionMode(javax.swing.JFileChooser.DIRECTORIES_ONLY);

        setDefaultCloseOperation(javax.swing.WindowConstants.EXIT_ON_CLOSE);
        setTitle("CosmosLauncher");

        runPanel.setBorder(javax.swing.BorderFactory.createEtchedBorder());
        runPanel.setPreferredSize(new java.awt.Dimension(567, 137));

        jLabel2.setText("QEMU");

        infoLabel1.setText("----");
        infoLabel1.setPreferredSize(new java.awt.Dimension(180, 15));

        jLabel3.setText("COSMOS");

        infoLabel2.setText("----");
        infoLabel2.setPreferredSize(new java.awt.Dimension(180, 15));

        jLabel4.setText("Module");

        infoLabel3.setText("----");
        infoLabel3.setPreferredSize(new java.awt.Dimension(180, 15));

        infoLabel4.setText("----");
        infoLabel4.setPreferredSize(new java.awt.Dimension(180, 15));

        jLabel5.setText("State");

        javax.swing.GroupLayout infoPanelLayout = new javax.swing.GroupLayout(infoPanel);
        infoPanel.setLayout(infoPanelLayout);
        infoPanelLayout.setHorizontalGroup(
            infoPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(infoPanelLayout.createSequentialGroup()
                .addContainerGap()
                .addGroup(infoPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(infoLabel1, javax.swing.GroupLayout.PREFERRED_SIZE, 68, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(jLabel2))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                .addGroup(infoPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(jLabel3)
                    .addComponent(infoLabel2, javax.swing.GroupLayout.PREFERRED_SIZE, 68, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addGap(18, 18, 18)
                .addGroup(infoPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(jLabel4)
                    .addComponent(infoLabel3, javax.swing.GroupLayout.PREFERRED_SIZE, 211, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addGap(13, 13, 13)
                .addGroup(infoPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addGroup(infoPanelLayout.createSequentialGroup()
                        .addComponent(jLabel5)
                        .addGap(0, 0, Short.MAX_VALUE))
                    .addComponent(infoLabel4, javax.swing.GroupLayout.PREFERRED_SIZE, 1, Short.MAX_VALUE))
                .addContainerGap())
        );
        infoPanelLayout.setVerticalGroup(
            infoPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(infoPanelLayout.createSequentialGroup()
                .addContainerGap()
                .addGroup(infoPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(jLabel2)
                    .addComponent(jLabel3)
                    .addComponent(jLabel4)
                    .addComponent(jLabel5))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(infoPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(infoLabel1, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(infoLabel2, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(infoLabel3, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(infoLabel4, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addContainerGap())
        );

        startCosmosButton.setBackground(new java.awt.Color(204, 255, 204));
        startCosmosButton.setText("Launch");
        startCosmosButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                startCosmosButtonActionPerformed(evt);
            }
        });

        stopCosmosButton.setBackground(new java.awt.Color(255, 204, 204));
        stopCosmosButton.setText("Stop");
        stopCosmosButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                stopCosmosButtonActionPerformed(evt);
            }
        });

        moduleComboBox.setModel(new javax.swing.DefaultComboBoxModel<>(new String[] { "Item 1", "Item 2", "Item 3", "Item 4" }));
        moduleComboBox.setPreferredSize(new java.awt.Dimension(206, 24));

        elapsedTimeLabel.setText("Elapsed: 00:00");
        elapsedTimeLabel.setMaximumSize(new java.awt.Dimension(66, 25));
        elapsedTimeLabel.setMinimumSize(new java.awt.Dimension(66, 25));
        elapsedTimeLabel.setPreferredSize(new java.awt.Dimension(66, 25));

        statusLabel.setText("Status: ");
        statusLabel.setPreferredSize(new java.awt.Dimension(146, 25));

        javax.swing.GroupLayout runPanelLayout = new javax.swing.GroupLayout(runPanel);
        runPanel.setLayout(runPanelLayout);
        runPanelLayout.setHorizontalGroup(
            runPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addComponent(infoPanel, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
            .addGroup(runPanelLayout.createSequentialGroup()
                .addComponent(startCosmosButton, javax.swing.GroupLayout.PREFERRED_SIZE, 85, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(11, 11, 11)
                .addComponent(stopCosmosButton)
                .addGap(18, 18, 18)
                .addComponent(moduleComboBox, 0, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                .addGap(29, 29, 29)
                .addComponent(elapsedTimeLabel, javax.swing.GroupLayout.PREFERRED_SIZE, 135, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addContainerGap())
            .addComponent(statusLabel, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
        );
        runPanelLayout.setVerticalGroup(
            runPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(runPanelLayout.createSequentialGroup()
                .addComponent(infoPanel, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                .addGroup(runPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(moduleComboBox, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(startCosmosButton)
                    .addComponent(stopCosmosButton)
                    .addComponent(elapsedTimeLabel, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                .addComponent(statusLabel, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(0, 0, Short.MAX_VALUE))
        );

        procPanel.setBorder(javax.swing.BorderFactory.createEtchedBorder());
        procPanel.setPreferredSize(new java.awt.Dimension(567, 127));

        procTable.setModel(new javax.swing.table.DefaultTableModel(
            new Object [][] {

            },
            new String [] {
                "Type", "Status", "UID", "PID", "Name"
            }
        ) {
            Class[] types = new Class [] {
                java.lang.String.class, java.lang.String.class, java.lang.String.class, java.lang.String.class, java.lang.String.class
            };
            boolean[] canEdit = new boolean [] {
                false, false, false, false, false
            };

            public Class getColumnClass(int columnIndex) {
                return types [columnIndex];
            }

            public boolean isCellEditable(int rowIndex, int columnIndex) {
                return canEdit [columnIndex];
            }
        });
        procScrollPane.setViewportView(procTable);

        javax.swing.GroupLayout procPanelLayout = new javax.swing.GroupLayout(procPanel);
        procPanel.setLayout(procPanelLayout);
        procPanelLayout.setHorizontalGroup(
            procPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGap(0, 0, Short.MAX_VALUE)
            .addGroup(procPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                .addComponent(procScrollPane, javax.swing.GroupLayout.DEFAULT_SIZE, 563, Short.MAX_VALUE))
        );
        procPanelLayout.setVerticalGroup(
            procPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGap(0, 124, Short.MAX_VALUE)
            .addGroup(procPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                .addComponent(procScrollPane, javax.swing.GroupLayout.DEFAULT_SIZE, 124, Short.MAX_VALUE))
        );

        controlTabbedPane.setBorder(javax.swing.BorderFactory.createEtchedBorder());
        controlTabbedPane.setPreferredSize(new java.awt.Dimension(389, 270));

        straceCheckBox.setText("Enable STrace on Launch");

        copyLogfilesButton.setBackground(new java.awt.Color(204, 204, 255));
        copyLogfilesButton.setText("Copy Logs");
        copyLogfilesButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                copyLogfilesButtonActionPerformed(evt);
            }
        });

        modpathButton.setText("Module Path");
        modpathButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                modpathButtonActionPerformed(evt);
            }
        });

        copyModuleButton.setBackground(new java.awt.Color(204, 204, 255));
        copyModuleButton.setText("Copy Module");
        copyModuleButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                copyModuleButtonActionPerformed(evt);
            }
        });

        logpathButton.setText("Logfile Path");
        logpathButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                logpathButtonActionPerformed(evt);
            }
        });

        javax.swing.GroupLayout setupPanelLayout = new javax.swing.GroupLayout(setupPanel);
        setupPanel.setLayout(setupPanelLayout);
        setupPanelLayout.setHorizontalGroup(
            setupPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(setupPanelLayout.createSequentialGroup()
                .addContainerGap()
                .addGroup(setupPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addGroup(setupPanelLayout.createSequentialGroup()
                        .addGroup(setupPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                            .addComponent(logpathButton, javax.swing.GroupLayout.PREFERRED_SIZE, 131, javax.swing.GroupLayout.PREFERRED_SIZE)
                            .addComponent(modpathButton, javax.swing.GroupLayout.PREFERRED_SIZE, 131, javax.swing.GroupLayout.PREFERRED_SIZE))
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addGroup(setupPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                            .addComponent(logpathTextField)
                            .addComponent(modpathTextField)))
                    .addGroup(setupPanelLayout.createSequentialGroup()
                        .addGroup(setupPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                            .addComponent(straceCheckBox)
                            .addComponent(copyModuleButton, javax.swing.GroupLayout.PREFERRED_SIZE, 131, javax.swing.GroupLayout.PREFERRED_SIZE)
                            .addComponent(copyLogfilesButton, javax.swing.GroupLayout.PREFERRED_SIZE, 131, javax.swing.GroupLayout.PREFERRED_SIZE))
                        .addGap(0, 154, Short.MAX_VALUE)))
                .addContainerGap())
        );
        setupPanelLayout.setVerticalGroup(
            setupPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(setupPanelLayout.createSequentialGroup()
                .addContainerGap()
                .addComponent(straceCheckBox)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED, 68, Short.MAX_VALUE)
                .addGroup(setupPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(modpathButton)
                    .addComponent(modpathTextField, javax.swing.GroupLayout.PREFERRED_SIZE, 25, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addGap(8, 8, 8)
                .addComponent(copyModuleButton)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                .addGroup(setupPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(logpathTextField, javax.swing.GroupLayout.PREFERRED_SIZE, 25, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(logpathButton))
                .addGap(8, 8, 8)
                .addComponent(copyLogfilesButton)
                .addContainerGap())
        );

        controlTabbedPane.addTab("Setup", setupPanel);

        jLabel1.setText("PID");

        commandExecuteButton.setText("Execute");
        commandExecuteButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                commandExecuteButtonActionPerformed(evt);
            }
        });

        commandComboBox.setModel(new javax.swing.DefaultComboBoxModel<>(new String[] { "getCosmosInfo", "getCosmosPartitions", "getCosmosProcesses", "getCosmosConnections", "getCosmosPorts", "getCosmosHmfm", "getScheduling", "getRunningProcesses", "getCosmosInitPid", "getModuleList" }));

        pidTextField.setMinimumSize(new java.awt.Dimension(4, 25));
        pidTextField.setPreferredSize(new java.awt.Dimension(60, 25));

        commandTerminateButton.setText("Terminate");
        commandTerminateButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                commandTerminateButtonActionPerformed(evt);
            }
        });

        termTypeComboBox.setModel(new javax.swing.DefaultComboBoxModel<>(new String[] { "SIGUSR1", "SIGTERM", "SIGKILL" }));

        javax.swing.GroupLayout debugPanelLayout = new javax.swing.GroupLayout(debugPanel);
        debugPanel.setLayout(debugPanelLayout);
        debugPanelLayout.setHorizontalGroup(
            debugPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(javax.swing.GroupLayout.Alignment.TRAILING, debugPanelLayout.createSequentialGroup()
                .addContainerGap(javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                .addComponent(jLabel1)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(pidTextField, javax.swing.GroupLayout.PREFERRED_SIZE, 47, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                .addGroup(debugPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING, false)
                    .addComponent(commandTerminateButton, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                    .addComponent(commandExecuteButton, javax.swing.GroupLayout.PREFERRED_SIZE, 106, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(debugPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING, false)
                    .addComponent(commandComboBox, javax.swing.GroupLayout.PREFERRED_SIZE, 150, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(termTypeComboBox, javax.swing.GroupLayout.PREFERRED_SIZE, 150, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addContainerGap())
        );
        debugPanelLayout.setVerticalGroup(
            debugPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(debugPanelLayout.createSequentialGroup()
                .addContainerGap()
                .addGroup(debugPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(commandComboBox, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(commandExecuteButton))
                .addGap(7, 7, 7)
                .addGroup(debugPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(termTypeComboBox, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(commandTerminateButton)
                    .addComponent(pidTextField, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(jLabel1))
                .addContainerGap(170, Short.MAX_VALUE))
        );

        controlTabbedPane.addTab("Debug", debugPanel);

        hmfmTable.setModel(new javax.swing.table.DefaultTableModel(
            new Object [][] {

            },
            new String [] {
                "Timestamp", "Process", "Error", "Name"
            }
        ) {
            Class[] types = new Class [] {
                java.lang.String.class, java.lang.String.class, java.lang.String.class, java.lang.String.class
            };
            boolean[] canEdit = new boolean [] {
                false, false, false, false
            };

            public Class getColumnClass(int columnIndex) {
                return types [columnIndex];
            }

            public boolean isCellEditable(int rowIndex, int columnIndex) {
                return canEdit [columnIndex];
            }
        });
        hmfmScrollPane.setViewportView(hmfmTable);

        controlTabbedPane.addTab("HMFM", hmfmScrollPane);

        debugScrollPane.setViewportView(debugTextPane);

        logTabbedPane.addTab("Debug", debugScrollPane);

        portTable.setModel(new javax.swing.table.DefaultTableModel(
            new Object [][] {

            },
            new String [] {
                "Source Port", "Port ID", "Partition", "Status", "Count", "Timestamp", "Dest Port", "Port ID", "Partition", "Status", "Count", "Timestamp"
            }
        ) {
            Class[] types = new Class [] {
                java.lang.String.class, java.lang.String.class, java.lang.String.class, java.lang.String.class, java.lang.String.class, java.lang.String.class, java.lang.String.class, java.lang.String.class, java.lang.String.class, java.lang.String.class, java.lang.String.class, java.lang.String.class
            };
            boolean[] canEdit = new boolean [] {
                false, false, false, false, false, false, false, false, false, false, false, false
            };

            public Class getColumnClass(int columnIndex) {
                return types [columnIndex];
            }

            public boolean isCellEditable(int rowIndex, int columnIndex) {
                return canEdit [columnIndex];
            }
        });
        portScrollPane.setViewportView(portTable);

        logTabbedPane.addTab("Ports", portScrollPane);

        javax.swing.GroupLayout layout = new javax.swing.GroupLayout(getContentPane());
        getContentPane().setLayout(layout);
        layout.setHorizontalGroup(
            layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(layout.createSequentialGroup()
                .addContainerGap()
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(logTabbedPane)
                    .addGroup(layout.createSequentialGroup()
                        .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING, false)
                            .addComponent(procPanel, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                            .addComponent(runPanel, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
                        .addGap(8, 8, 8)
                        .addComponent(controlTabbedPane, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)))
                .addContainerGap())
        );
        layout.setVerticalGroup(
            layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(layout.createSequentialGroup()
                .addContainerGap()
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING, false)
                    .addGroup(layout.createSequentialGroup()
                        .addComponent(runPanel, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(procPanel, javax.swing.GroupLayout.DEFAULT_SIZE, 128, Short.MAX_VALUE))
                    .addComponent(controlTabbedPane, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                .addComponent(logTabbedPane, javax.swing.GroupLayout.DEFAULT_SIZE, 316, Short.MAX_VALUE)
                .addContainerGap())
        );

        pack();
    }// </editor-fold>//GEN-END:initComponents

    private void commandExecuteButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_commandExecuteButtonActionPerformed
       String command = (String)commandComboBox.getSelectedItem();
        switch(command) {
            // the following execute in the background
            case "getCosmosInfo":           cosmosInterface.getCosmosInfo ();        break;
            case "getCosmosPartitions":     cosmosInterface.getCosmosPartitions ();  break;
            case "getCosmosProcesses":      cosmosInterface.getCosmosProcesses ();   break;
            case "getCosmosConnections":    cosmosInterface.getCosmosConnections (); break;
            case "getCosmosPorts":          cosmosInterface.getCosmosPorts ();       break;
            case "getCosmosHmfm":           cosmosInterface.getCosmosHmfm ();        break;
            case "getScheduling":           cosmosInterface.getScheduling ();        break;
            case "getRunningProcesses":     cosmosInterface.getRunningProcesses ();  break;
            case "getModuleList":           cosmosInterface.getModuleList();         break;
            // the following execute immediately
            case "getCosmosInitPid":        runSshImmediateCommand(command);
                // update the cosmos_init pid textfield here so Terminate can use it
                this.pidTextField.setText(cosmosInterface.getNumericResponse().toString());
                break;
            default:
                break;
        }
    }//GEN-LAST:event_commandExecuteButtonActionPerformed

    private void startCosmosButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_startCosmosButtonActionPerformed
        String module = (String)moduleComboBox.getSelectedItem();
        if (module == null || module.isEmpty()) {
            statusLabel.setText("No module selected to start");
            return;
        }

        // start the selected module
        loopCount = 0;
        cosmosState = CosmosState.starting;
        int exitcode = cosmosInterface.cosmosInitStart(module, this.straceCheckBox.isSelected());
        showSshImmediateCommand("cosmosInitStart", exitcode);
        
        // restart the elapsed time
        elapsedTimer.start();
            
        // make sure the stop function is disabled
        stopCounter = 0;

        // get pid of cosmos_init
        runSshImmediateCommand("getCosmosInitPid");
        cosmosPid = cosmosInterface.getNumericResponse();
        cosmosInterface.setCosmosPid(cosmosPid);

        // reset the module information
        procTableInfo.resetList();
        cosmosPartList.clear();
        cosmosProcList.clear();

        // start the polling of results
        cosmosInterface.getIsProcessRunning();
    }//GEN-LAST:event_startCosmosButtonActionPerformed

    private void modpathButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_modpathButtonActionPerformed
        this.modulePathChooser.setMultiSelectionEnabled(false);
        int retVal = this.modulePathChooser.showOpenDialog(this);
        if (retVal == JFileChooser.APPROVE_OPTION) {
            File file = this.modulePathChooser.getSelectedFile();
            String modName = file.getName();
            String modPath = file.getAbsolutePath();

            // now check to see if required files are present
            String xmlFile = modPath + "/xml/" + modName + ".xml";
            file = new File(xmlFile);
            if (!file.isFile()) {
                JOptionPane.showMessageDialog(null,
                    "Selected path is missing the required file: " + modName + ".xml",
                    "Missing xml file", JOptionPane.ERROR_MESSAGE);
                return;
            }

            moduleName = modName;
            this.modpathTextField.setText(modPath);
        }
    }//GEN-LAST:event_modpathButtonActionPerformed

    private void commandTerminateButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_commandTerminateButtonActionPerformed
        String termType = (String)termTypeComboBox.getSelectedItem();
        String pid = this.pidTextField.getText();
        if (!pid.isEmpty()) {
            int pidval = Integer.parseInt(pid);
            if (pidval > 0) {
                cosmosInterface.cosmosInitStop(termType, pidval);
            }
        }
    }//GEN-LAST:event_commandTerminateButtonActionPerformed

    private void copyLogfilesButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_copyLogfilesButtonActionPerformed
        // the module being copied is assumed to be one that is selected for launch
        String module = (String)moduleComboBox.getSelectedItem();
        if (module == null || module.isEmpty()) {
            statusLabel.setText("No module selected to copy from");
        }

        // make sure a destination path is specified for the logfiles
        String logpathname = this.logpathTextField.getText();
        if (logpathname == null || logpathname.isEmpty()) {
            statusLabel.setText("No log Path specified.");
            debug.print(DebugMessage.StatusType.Error, statusLabel.getText());
            return;
        }

        // create the log file directory
        // (delete first if it currently exists to eliminate any old log files)
        String path = logpathname + "/Logs/" + module + "/";
        File logpath = new File(path);
        if (logpath.isDirectory()) {
            try {
                FileUtils.deleteDirectory(logpath);
            } catch (IOException ex) {
                debug.print(DebugMessage.StatusType.Error, ex + "(deleting Logs folder)");
            }
        }
        logpath.mkdirs();
            
        statusLabel.setText("Copying log files from COSMOS. This may take awhile...");

        // now copy the files over
        int exitcode = cosmosInterface.copyLogFiles (logpathname, module);
        showSshImmediateCommand("copyLogFiles", exitcode);
        
        // TODO: remove any previous tabs that are log files
        while (removeTextTab(logTabbedPane, "stdout") != 0) {}
        while (removeTextTab(logTabbedPane, "stderr") != 0) {}
        while (removeTextTab(logTabbedPane, "kernel") != 0) {}
        
        // now add the new log files to the tabbed frame
        if (!logpath.isDirectory()) {
            statusLabel.setText("No Log file directory found.");
            debug.print(DebugMessage.StatusType.Error, statusLabel.getText());
            return;
        }
        String[] filelist = logpath.list();
        if (filelist == null || filelist.length == 0 ) {
            statusLabel.setText("No Log files found.");
            debug.print(DebugMessage.StatusType.Error, statusLabel.getText());
            return;
        }

        int count = 0;
        Arrays.sort(filelist);
        for (String filename : filelist) {
            int offset = filename.lastIndexOf(".");
            if (offset < 0) continue; // invalid file
            String basename = filename.substring(offset+1);
            String logname = filename.substring(0, offset);
            if (basename.equals("stdout") || basename.equals("stderr") || basename.equals("kernel")) {
                offset = logname.lastIndexOf("/");
                logname = offset < 0 ? logname : logname.substring(offset+1);
                offset = logname.lastIndexOf("_");
                logname = offset < 0 ? "" : "." + logname.substring(offset+1);
                String tabname = basename + logname;

                addTextTab (logTabbedPane, tabname, path + filename);
                debug.print(DebugMessage.StatusType.Info, "Added tab: " + tabname);
                ++count;
            }
        }
        statusLabel.setText("Log file copy completed: " + count + " files");
    }//GEN-LAST:event_copyLogfilesButtonActionPerformed

    private void stopCosmosButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_stopCosmosButtonActionPerformed
        stopCounter = 3;
        stopDelay = 10;
        cosmosInterface.cosmosInitStop ("SIGUSR1", cosmosPid);
        cosmosState = CosmosState.stopping;
        cosmosInterface.getIsProcessRunning();
    }//GEN-LAST:event_stopCosmosButtonActionPerformed

    private void copyModuleButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_copyModuleButtonActionPerformed
        statusLabel.setText("Copying module files to COSMOS. This may take awhile...");
        cosmosState = CosmosState.copymodule;
        int exitcode = copyModuleFiles(this.modpathTextField.getText(), moduleName);
        if (exitcode == 0) {
            statusLabel.setText("Module files successfully copied");
            // rerun module list to update for this entry
            cosmosInterface.getModuleList();
        }
    }//GEN-LAST:event_copyModuleButtonActionPerformed

    private void logpathButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_logpathButtonActionPerformed
        this.logPathChooser.setMultiSelectionEnabled(false);
        int retVal = this.logPathChooser.showOpenDialog(this);
        if (retVal == JFileChooser.APPROVE_OPTION) {
            File file = this.logPathChooser.getSelectedFile();
            String logPath = file.getAbsolutePath();

            // save the selected path
            this.logpathTextField.setText(logPath);
        }
    }//GEN-LAST:event_logpathButtonActionPerformed

    /**
     * @param args the command line arguments
     */
    public static void main(String args[]) {
        /* Set the Nimbus look and feel */
        //<editor-fold defaultstate="collapsed" desc=" Look and feel setting code (optional) ">
        /* If Nimbus (introduced in Java SE 6) is not available, stay with the default look and feel.
         * For details see http://download.oracle.com/javase/tutorial/uiswing/lookandfeel/plaf.html 
         */
        try {
            for (javax.swing.UIManager.LookAndFeelInfo info : javax.swing.UIManager.getInstalledLookAndFeels()) {
                if ("Nimbus".equals(info.getName())) {
                    javax.swing.UIManager.setLookAndFeel(info.getClassName());
                    break;
                }
            }
        } catch (ClassNotFoundException ex) {
            java.util.logging.Logger.getLogger(LauncherFrame.class.getName()).log(java.util.logging.Level.SEVERE, null, ex);
        } catch (InstantiationException ex) {
            java.util.logging.Logger.getLogger(LauncherFrame.class.getName()).log(java.util.logging.Level.SEVERE, null, ex);
        } catch (IllegalAccessException ex) {
            java.util.logging.Logger.getLogger(LauncherFrame.class.getName()).log(java.util.logging.Level.SEVERE, null, ex);
        } catch (javax.swing.UnsupportedLookAndFeelException ex) {
            java.util.logging.Logger.getLogger(LauncherFrame.class.getName()).log(java.util.logging.Level.SEVERE, null, ex);
        }
        //</editor-fold>

        /* Create and display the form */
        java.awt.EventQueue.invokeLater(new Runnable() {
            public void run() {
                new LauncherFrame().setVisible(true);
            }
        });
    }

   
    public enum CosmosState {
        initialization, idle, starting, stopping, copymodule;
    }
    
    // the info returned from the /proc/cosmos/partitions query
    public class CosmosPartitionInfo {
        public String  partid;
        public String  type;
        public String  master;
        public boolean bValid;      // true if part found in getRunningProcesses
        public boolean bActive;     // true if part found in getCosmosPartitions
    }
    
    // the info returned from the /proc/cosmos/processes query
    public class CosmosProcessInfo {
        public String pid;
        public String state;
        public String suspend;
        public String name;
    }

    private DebugMessage         debug;
    private TableInfo            procTableInfo;
    private TableInfo            hmfmTableInfo;
    private TableInfo            portTableInfo;
    private ArrayList<CosmosPartitionInfo>  cosmosPartList;
    private ArrayList<CosmosProcessInfo>    cosmosProcList;
    private boolean              bCosmosRunning;
    private CosmosState          cosmosState;

    private final ElapsedTimer   elapsedTimer;
    private final SshCommand     cosmosInterface;
    private String               moduleName;
    private Integer              cosmosPid;
    private int                  stopCounter;  // when stopping, this sets the level to apply
    private int                  stopDelay;    // this gives a delay between stop attempts
    private int                  loopCount;
    
    // Variables declaration - do not modify//GEN-BEGIN:variables
    private javax.swing.JComboBox<String> commandComboBox;
    private javax.swing.JButton commandExecuteButton;
    private javax.swing.JButton commandTerminateButton;
    private javax.swing.JTabbedPane controlTabbedPane;
    private javax.swing.JButton copyLogfilesButton;
    private javax.swing.JButton copyModuleButton;
    private javax.swing.JPanel debugPanel;
    private javax.swing.JScrollPane debugScrollPane;
    private javax.swing.JTextPane debugTextPane;
    private javax.swing.JLabel elapsedTimeLabel;
    private javax.swing.JScrollPane hmfmScrollPane;
    private javax.swing.JTable hmfmTable;
    private javax.swing.JLabel infoLabel1;
    private javax.swing.JLabel infoLabel2;
    private javax.swing.JLabel infoLabel3;
    private javax.swing.JLabel infoLabel4;
    private javax.swing.JPanel infoPanel;
    private javax.swing.JLabel jLabel1;
    private javax.swing.JLabel jLabel2;
    private javax.swing.JLabel jLabel3;
    private javax.swing.JLabel jLabel4;
    private javax.swing.JLabel jLabel5;
    private javax.swing.JFileChooser logPathChooser;
    private javax.swing.JTabbedPane logTabbedPane;
    private javax.swing.JButton logpathButton;
    private javax.swing.JTextField logpathTextField;
    private javax.swing.JButton modpathButton;
    private javax.swing.JTextField modpathTextField;
    private javax.swing.JComboBox<String> moduleComboBox;
    private javax.swing.JFileChooser modulePathChooser;
    private javax.swing.JTextField pidTextField;
    private javax.swing.JScrollPane portScrollPane;
    private javax.swing.JTable portTable;
    private javax.swing.JPanel procPanel;
    private javax.swing.JScrollPane procScrollPane;
    private javax.swing.JTable procTable;
    private javax.swing.JPanel runPanel;
    private javax.swing.JPanel setupPanel;
    private javax.swing.JButton startCosmosButton;
    private javax.swing.JLabel statusLabel;
    private javax.swing.JButton stopCosmosButton;
    private javax.swing.JCheckBox straceCheckBox;
    private javax.swing.JComboBox<String> termTypeComboBox;
    // End of variables declaration//GEN-END:variables
}
