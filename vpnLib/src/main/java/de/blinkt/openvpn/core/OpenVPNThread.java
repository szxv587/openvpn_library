/*
 * Copyright (c) 2012-2016 Arne Schwabe
 * Distributed under the GNU GPL v2 with additional terms. For full terms see the file doc/LICENSE.txt
 */

package de.blinkt.openvpn.core;

import android.annotation.SuppressLint;
import android.util.Log;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedList;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.blinkt.openvpn.R;

public class OpenVPNThread implements Runnable {
    private static final String DUMP_PATH_STRING = "Dump path: ";
    @SuppressLint("SdCardPath")
    private static final String BROKEN_PIE_SUPPORT = "/data/data/de.blinkt.openvpn/cache/pievpn";
    private final static String BROKEN_PIE_SUPPORT2 = "syntax error";
    private static final String TAG = "OpenVPN";
    // 1380308330.240114 18000002 Send to HTTP proxy: 'X-Online-Host: bla.blabla.com'
    private static final Pattern LOG_PATTERN = Pattern.compile("(\\d+).(\\d+) ([0-9a-f])+ (.*)");
    public static final int M_FATAL = (1 << 4);
    public static final int M_NONFATAL = (1 << 5);
    public static final int M_WARN = (1 << 6);
    public static final int M_DEBUG = (1 << 7);
    private String[] mArgv;
    private static Process mProcess;
    private String mNativeDir;
    private String mTmpDir;
    private static OpenVPNService mService;
    private String mDumpPath;
    private boolean mBrokenPie = false;
    private boolean mNoProcessExitStatus = false;

    public OpenVPNThread(OpenVPNService service, String[] argv, String nativelibdir, String tmpdir) {
        mArgv = argv;
        mNativeDir = nativelibdir;
        mTmpDir = tmpdir;
        mService = service;
    }

    public OpenVPNThread() {
    }

    public void stopProcess() {
        if (mProcess != null)
            mProcess.destroy();
    }

    void setReplaceConnection() {
        mNoProcessExitStatus = true;
    }

    @Override
    public void run() {
        try {
            Log.i(TAG, "11111111111111111111");
            Log.i(TAG, "Starting openvpn");
            Log.i(TAG, "songzixuan: "+Arrays.toString(mArgv));
            startOpenVPNThreadArgs(mArgv);
            Log.i(TAG, "22222222222222222222");
            Log.i(TAG, "OpenVPN process exited");
        } catch (Exception e) {
            VpnStatus.logException("Starting OpenVPN Thread", e);
            Log.e(TAG, "OpenVPNThread Got " + e.toString());
        } finally {
            Log.e(TAG, "33333333333333333");
            int exitvalue = 0;
            try {
                if (mProcess != null)
                    exitvalue = mProcess.waitFor();
                Log.i(TAG, "111");
            } catch (IllegalThreadStateException ite) {
                Log.e(TAG, "songzixuan: "+ite.getMessage());
                VpnStatus.logError("Illegal Thread state: " + ite.getLocalizedMessage());
            } catch (InterruptedException ie) {
                Log.e(TAG, "songzixuan: "+ie.getMessage());
                VpnStatus.logError("InterruptedException: " + ie.getLocalizedMessage());
            }
            Log.i(TAG, "222");
            if (exitvalue != 0) {
                Log.i(TAG, "333");
                VpnStatus.logError("Process exited with exit value " + exitvalue);
                if (mBrokenPie) {
                    Log.i(TAG, "444");
                    /* This will probably fail since the NoPIE binary is probably not written */
                    String[] noPieArgv = VPNLaunchHelper.replacePieWithNoPie(mArgv);

                    // We are already noPIE, nothing to gain
                    if (!noPieArgv.equals(mArgv)) {
                        Log.i(TAG, "555");
                        mArgv = noPieArgv;
                        VpnStatus.logInfo("PIE Version could not be executed. Trying no PIE version");
                        run();
                    }

                }

            }

            if (!mNoProcessExitStatus)
                Log.i(TAG, "666");
                VpnStatus.updateStateString("NOPROCESS", "No process running.", R.string.state_noprocess, ConnectionStatus.LEVEL_NOTCONNECTED);

            if (mDumpPath != null) {
                Log.i(TAG, "777");
                try {
                    Log.i(TAG, "888");
                    BufferedWriter logout = new BufferedWriter(new FileWriter(mDumpPath + ".log"));
                    SimpleDateFormat timeformat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.GERMAN);
                    for (LogItem li : VpnStatus.getlogbuffer()) {
                        String time = timeformat.format(new Date(li.getLogtime()));
                        logout.write(time + " " + li.getString(mService) + "\n");
                    }
                    logout.close();
                    VpnStatus.logError(R.string.minidump_generated);
                } catch (IOException e) {
                    Log.e(TAG, "songzixuan: "+e.getMessage());
                    VpnStatus.logError("Writing minidump log: " + e.getLocalizedMessage());
                }
            }
            Log.i(TAG, "999");
//            if (!mNoProcessExitStatus) {
//                Log.i(TAG, "9999");
//                mService.openvpnStopped();
//            }
            Log.i(TAG, "Exiting");
        }
    }

    public static boolean stop() {
        mService.openvpnStopped();
        if (mProcess != null)
            mProcess.destroy();
        return true;
    }

    private void startOpenVPNThreadArgs(String[] argv) {
        LinkedList<String> argvlist = new LinkedList<>();

        Collections.addAll(argvlist, argv);

        ProcessBuilder pb = new ProcessBuilder(argvlist);
        // Hack O rama

        String lbpath = genLibraryPath(argv, pb);
        Log.i(TAG, lbpath);

        pb.environment().put("LD_LIBRARY_PATH", lbpath);
        pb.environment().put("TMPDIR", mTmpDir);

        pb.redirectErrorStream(true);
        try {
            Log.i(TAG, "11111");
            mProcess = pb.start();
            // Close the output, since we don't need it
//            mProcess.getOutputStream().close();
            Log.i(TAG, "22222");
            InputStream in = mProcess.getInputStream();
            BufferedReader br = new BufferedReader(new InputStreamReader(in));
            Log.i(TAG, "33333");
            String line;
            while ((line = br.readLine()) != null) {
                Log.i(TAG, "888: line == "+line);
                if (line.startsWith(DUMP_PATH_STRING))
                    mDumpPath = line.substring(DUMP_PATH_STRING.length());

                if (line.startsWith(BROKEN_PIE_SUPPORT) || line.contains(BROKEN_PIE_SUPPORT2))
                    mBrokenPie = true;

                Matcher m = LOG_PATTERN.matcher(line);
                int logerror = 0;
                if (m.matches()) {
                    int flags = Integer.parseInt(m.group(3), 16);
                    String msg = m.group(4);
                    int logLevel = flags & 0x0F;

                    VpnStatus.LogLevel logStatus = VpnStatus.LogLevel.INFO;

                    if ((flags & M_FATAL) != 0)
                        logStatus = VpnStatus.LogLevel.ERROR;
                    else if ((flags & M_NONFATAL) != 0)
                        logStatus = VpnStatus.LogLevel.WARNING;
                    else if ((flags & M_WARN) != 0)
                        logStatus = VpnStatus.LogLevel.WARNING;
                    else if ((flags & M_DEBUG) != 0)
                        logStatus = VpnStatus.LogLevel.VERBOSE;

                    assert msg != null;
                    if (msg.startsWith("MANAGEMENT: CMD"))
                        logLevel = Math.max(4, logLevel);

                    if ((msg.endsWith("md too weak") && msg.startsWith("OpenSSL: error")) || msg.contains("error:140AB18E"))
                        logerror = 1;

                    VpnStatus.logMessageOpenVPN(logStatus, logLevel, msg);
                    if (logerror == 1)
                        VpnStatus.logError("OpenSSL reported a certificate with a weak hash, please the in app FAQ about weak hashes");

                } else {
                    VpnStatus.logInfo("P:" + line);
                }

                if (Thread.interrupted()) {
                    throw new InterruptedException("OpenVpn process was killed form java code");
                }
            }
            Log.i(TAG, "44444");
        } catch (InterruptedException | IOException e) {
            Log.e(TAG, "songzixuan: "+e.getMessage());
            VpnStatus.logException("Error reading from output of OpenVPN process", e);
            stopProcess();
        }


    }

    private String genLibraryPath(String[] argv, ProcessBuilder pb) {
        // Hack until I find a good way to get the real library path
        String applibpath = argv[0].replaceFirst("/cache/.*$", "/lib");

        String lbpath = pb.environment().get("LD_LIBRARY_PATH");
        if (lbpath == null)
            lbpath = applibpath;
        else
            lbpath = applibpath + ":" + lbpath;

        if (!applibpath.equals(mNativeDir)) {
            lbpath = mNativeDir + ":" + lbpath;
        }
        return lbpath;
    }
}
