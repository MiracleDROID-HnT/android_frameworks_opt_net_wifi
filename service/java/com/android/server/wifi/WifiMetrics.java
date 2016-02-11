/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.wifi;

import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.os.SystemClock;
import android.util.Base64;
import android.util.SparseArray;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * Provides storage for wireless connectivity metrics, as they are generated.
 * Metrics logged by this class include:
 *   Aggregated connection stats (num of connections, num of failures, ...)
 *   Discrete connection event stats (time, duration, failure codes, ...)
 *   Router details (technology type, authentication type, ...)
 *   Scan stats
 */
public class WifiMetrics {
    private static final String TAG = "WifiMetrics";
    private final Object mLock = new Object();
    private static final int MAX_CONNECTION_EVENTS = 256;
    /**
     * Metrics are stored within an instance of the WifiLog proto during runtime,
     * The ConnectionEvent, SystemStateEntries & ScanReturnEntries metrics are stored during
     * runtime in member lists of this WifiMetrics class, with the final WifiLog proto being pieced
     * together at dump-time
     */
    private final WifiMetricsProto.WifiLog mWifiLogProto;
    /**
     * Session information that gets logged for every Wifi connection attempt.
     */
    private final List<ConnectionEvent> mConnectionEventList;
    /**
     * The latest started (but un-ended) connection attempt
     */
    private ConnectionEvent mCurrentConnectionEvent;
    /**
     * Count of number of times each scan return code, indexed by WifiLog.ScanReturnCode
     */
    private final SparseArray<WifiMetricsProto.WifiLog.ScanReturnEntry> mScanReturnEntries;
    /**
     * Mapping of system state to the counts of scans requested in that wifi state * screenOn
     * combination. Indexed by WifiLog.WifiState * (1 + screenOn)
     */
    private final SparseArray<WifiMetricsProto.WifiLog.WifiSystemStateEntry>
            mWifiSystemStateEntries;

    class RouterFingerPrint {
        private WifiMetricsProto.RouterFingerPrint mRouterFingerPrintProto;
        RouterFingerPrint() {
            mRouterFingerPrintProto = new WifiMetricsProto.RouterFingerPrint();
        }

        public String toString() {
            StringBuilder sb = new StringBuilder();
            synchronized (mLock) {
                sb.append("mConnectionEvent.roamType=" + mRouterFingerPrintProto.roamType);
                sb.append(", mChannelInfo=" + mRouterFingerPrintProto.channelInfo);
                sb.append(", mDtim=" + mRouterFingerPrintProto.dtim);
                sb.append(", mAuthentication=" + mRouterFingerPrintProto.authentication);
                sb.append(", mHidden=" + mRouterFingerPrintProto.hidden);
                sb.append(", mRouterTechnology=" + mRouterFingerPrintProto.routerTechnology);
                sb.append(", mSupportsIpv6=" + mRouterFingerPrintProto.supportsIpv6);
            }
            return sb.toString();
        }
        public void updateFromWifiConfiguration(WifiConfiguration config) {
            if (config != null) {
                /*<TODO>
                mRouterFingerPrintProto.roamType
                mRouterFingerPrintProto.dtim
                mRouterFingerPrintProto.routerTechnology
                mRouterFingerPrintProto.supportsIpv6
                */
                if (config.allowedAuthAlgorithms != null
                        && config.allowedAuthAlgorithms.get(WifiConfiguration.AuthAlgorithm.OPEN)) {
                    mRouterFingerPrintProto.authentication =
                            WifiMetricsProto.RouterFingerPrint.AUTH_OPEN;
                } else if (config.isEnterprise()) {
                    mRouterFingerPrintProto.authentication =
                            WifiMetricsProto.RouterFingerPrint.AUTH_ENTERPRISE;
                } else {
                    mRouterFingerPrintProto.authentication =
                            WifiMetricsProto.RouterFingerPrint.AUTH_PERSONAL;
                }
                mRouterFingerPrintProto.hidden = config.hiddenSSID;
                mRouterFingerPrintProto.channelInfo = config.apChannel;

            }
        }
    }

    /**
     * Log event, tracking the start time, end time and result of a wireless connection attempt.
     */
    class ConnectionEvent {
        WifiMetricsProto.ConnectionEvent mConnectionEvent;
        RouterFingerPrint mRouterFingerPrint;
        private long mRealStartTime;
        /**
         * Bitset tracking the capture completeness of this connection event bit 1='Event started',
         * bit 2='Event ended' value = 3 for capture completeness
         */
        private int mEventCompleteness;
        private long mRealEndTime;

        //<TODO> Move these constants into a wifi.proto Enum
        // Level 2 Failure Codes
        // Failure is unknown
        public static final int LLF_UNKNOWN = 0;
        // NONE
        public static final int LLF_NONE = 1;
        // ASSOCIATION_REJECTION_EVENT
        public static final int LLF_ASSOCIATION_REJECTION = 2;
        // AUTHENTICATION_FAILURE_EVENT
        public static final int LLF_AUTHENTICATION_FAILURE = 3;
        // SSID_TEMP_DISABLED (Also Auth failure)
        public static final int LLF_SSID_TEMP_DISABLED = 4;
        // CONNECT_NETWORK_FAILED
        public static final int LLF_CONNECT_NETWORK_FAILED = 5;
        // NETWORK_DISCONNECTION_EVENT
        public static final int LLF_NETWORK_DISCONNECTION = 6;

        private ConnectionEvent() {
            mConnectionEvent = new WifiMetricsProto.ConnectionEvent();
            mConnectionEvent.startTimeMillis = -1;
            mRealEndTime = -1;
            mConnectionEvent.durationTakenToConnectMillis = -1;
            mRouterFingerPrint = new RouterFingerPrint();
            mConnectionEvent.routerFingerprint = mRouterFingerPrint.mRouterFingerPrintProto;
            mConnectionEvent.signalStrength = -1;
            mConnectionEvent.roamType = WifiMetricsProto.ConnectionEvent.ROAM_UNKNOWN;
            mConnectionEvent.connectionResult = -1;
            mConnectionEvent.level2FailureCode = -1;
            mConnectionEvent.connectivityLevelFailureCode =
                    WifiMetricsProto.ConnectionEvent.HLF_UNKNOWN;
            mConnectionEvent.automaticBugReportTaken = false;
            mEventCompleteness = 0;
        }

        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("startTime=");
            Calendar c = Calendar.getInstance();
            synchronized (mLock) {
                c.setTimeInMillis(mConnectionEvent.startTimeMillis);
                sb.append(mConnectionEvent.startTimeMillis == 0 ? "            <null>" :
                        String.format("%tm-%td %tH:%tM:%tS.%tL", c, c, c, c, c, c));
                sb.append(", endTime=");
                c.setTimeInMillis(mRealEndTime);
                sb.append(mRealEndTime == 0 ? "            <null>" :
                        String.format("%tm-%td %tH:%tM:%tS.%tL", c, c, c, c, c, c));
                sb.append(", durationTakenToConnectMillis=");
                sb.append(mConnectionEvent.durationTakenToConnectMillis);
                sb.append(", roamType=");
                switch(mConnectionEvent.roamType){
                    case 1:
                        sb.append("ROAM_NONE");
                        break;
                    case 2:
                        sb.append("ROAM_DBDC");
                        break;
                    case 3:
                        sb.append("ROAM_ENTERPRISE");
                        break;
                    case 4:
                        sb.append("ROAM_USER_SELECTED");
                        break;
                    case 5:
                        sb.append("ROAM_UNRELATED");
                        break;
                    default:
                        sb.append("ROAM_UNKNOWN");
                }
                sb.append(", level2FailureCode=");
                sb.append(mConnectionEvent.level2FailureCode);
                sb.append(", connectivityLevelFailureCode=");
                sb.append(mConnectionEvent.connectivityLevelFailureCode);
                sb.append(", mEventCompleteness=");
                sb.append(mEventCompleteness);
                sb.append("\n  ");
                sb.append("mRouterFingerprint: ");
                sb.append(mRouterFingerPrint.toString());
            }
            return sb.toString();
        }
    }

    public WifiMetrics() {
        mWifiLogProto = new WifiMetricsProto.WifiLog();
        mConnectionEventList = new ArrayList<>();
        mCurrentConnectionEvent = null;
        mScanReturnEntries = new SparseArray<WifiMetricsProto.WifiLog.ScanReturnEntry>();
        mWifiSystemStateEntries = new SparseArray<WifiMetricsProto.WifiLog.WifiSystemStateEntry>();
    }

    /**
     * Create a new connection event. Call when wifi attempts to make a new network connection
     * If there is a current 'un-ended' connection event, it will be ended with UNKNOWN connectivity
     * failure code.
     * Gathers and sets the RouterFingerPrint data as well
     *
     * @param wifiInfo WifiInfo for the current connection attempt, used for connection metrics
     * @param roamType Roam type that caused connection attempt, see WifiMetricsProto.WifiLog.ROAM_X
     */
    public void startConnectionEvent(WifiInfo wifiInfo, WifiConfiguration config, int roamType) {
        synchronized (mLock) {
            if (mConnectionEventList.size() <= MAX_CONNECTION_EVENTS) {
                mCurrentConnectionEvent = new ConnectionEvent();
                mCurrentConnectionEvent.mEventCompleteness |= 1;
                mCurrentConnectionEvent.mConnectionEvent.startTimeMillis =
                        System.currentTimeMillis();
                mCurrentConnectionEvent.mConnectionEvent.roamType = roamType;
                mCurrentConnectionEvent.mRouterFingerPrint.updateFromWifiConfiguration(config);
                if (wifiInfo != null) {
                    mCurrentConnectionEvent.mConnectionEvent.signalStrength = wifiInfo.getRssi();
                }
                mCurrentConnectionEvent.mRealStartTime = SystemClock.elapsedRealtime();
                mConnectionEventList.add(mCurrentConnectionEvent);
            }
        }
    }

    public void startConnectionEvent(WifiInfo wifiInfo) {
        startConnectionEvent(wifiInfo, null, WifiMetricsProto.ConnectionEvent.ROAM_NONE);
    }

    /**
     * set the RoamType of the current ConnectionEvent (if any)
     */
    public void setConnectionEventRoamType(int roamType) {
        if (mCurrentConnectionEvent != null) {
            mCurrentConnectionEvent.mConnectionEvent.roamType = roamType;
        }
    }
    /**
     * End a Connection event record. Call when wifi connection attempt succeeds or fails.
     * If a Connection event has not been started and is active when .end is called, a new one is
     * created with zero duration.
     *
     * @param level2FailureCode Level 2 failure code returned by supplicant
     * @param connectivityFailureCode WifiMetricsProto.ConnectionEvent.HLF_X
     */
    public void endConnectionEvent(int level2FailureCode, int connectivityFailureCode) {
        synchronized (mLock) {
            if (mCurrentConnectionEvent != null) {
                boolean result = (level2FailureCode == 1)
                        && (connectivityFailureCode == WifiMetricsProto.ConnectionEvent.HLF_NONE);
                mCurrentConnectionEvent.mConnectionEvent.connectionResult = result ? 1 : 0;
                mCurrentConnectionEvent.mEventCompleteness |= 2;
                mCurrentConnectionEvent.mRealEndTime = SystemClock.elapsedRealtime();
                mCurrentConnectionEvent.mConnectionEvent.durationTakenToConnectMillis = (int)
                        (mCurrentConnectionEvent.mRealEndTime
                        - mCurrentConnectionEvent.mRealStartTime);
                mCurrentConnectionEvent.mConnectionEvent.level2FailureCode = level2FailureCode;
                mCurrentConnectionEvent.mConnectionEvent.connectivityLevelFailureCode =
                        connectivityFailureCode;
                //ConnectionEvent already added to ConnectionEvents List
                mCurrentConnectionEvent = null;
            }
        }
    }

    void setNumSavedNetworks(int num) {
        synchronized (mLock) {
            mWifiLogProto.numSavedNetworks = num;
        }
    }

    void setNumOpenNetworks(int num) {
        synchronized (mLock) {
            mWifiLogProto.numOpenNetworks = num;
        }
    }

    void setNumPersonalNetworks(int num) {
        synchronized (mLock) {
            mWifiLogProto.numPersonalNetworks = num;
        }
    }

    void setNumEnterpriseNetworks(int num) {
        synchronized (mLock) {
            mWifiLogProto.numEnterpriseNetworks = num;
        }
    }

    void setNumNetworksAddedByUser(int num) {
        synchronized (mLock) {
            mWifiLogProto.numNetworksAddedByUser = num;
        }
    }

    void setNumNetworksAddedByApps(int num) {
        synchronized (mLock) {
            mWifiLogProto.numNetworksAddedByApps = num;
        }
    }

    void setIsLocationEnabled(boolean enabled) {
        synchronized (mLock) {
            mWifiLogProto.isLocationEnabled = enabled;
        }
    }

    void setIsScanningAlwaysEnabled(boolean enabled) {
        synchronized (mLock) {
            mWifiLogProto.isScanningAlwaysEnabled = enabled;
        }
    }

    /**
     * Increment Airplane mode toggle count
     */
    public void incrementAirplaneToggleCount() {
        synchronized (mLock) {
            mWifiLogProto.numWifiToggledViaAirplane++;
        }
    }

    /**
     * Increment Wifi Toggle count
     */
    public void incrementWifiToggleCount() {
        synchronized (mLock) {
            mWifiLogProto.numWifiToggledViaSettings++;
        }
    }

    /**
     * Increment Non Empty Scan Results count
     */
    public void incrementNonEmptyScanResultCount() {
        synchronized (mLock) {
            mWifiLogProto.numNonEmptyScanResults++;
        }
    }

    /**
     * Increment Empty Scan Results count
     */
    public void incrementEmptyScanResultCount() {
        synchronized (mLock) {
            mWifiLogProto.numEmptyScanResults++;
        }
    }

    /**
     * Increment count of scan return code occurrence
     *
     * @param scanReturnCode Return code from scan attempt WifiMetricsProto.WifiLog.SCAN_X
     */
    public void incrementScanReturnEntry(int scanReturnCode) {
        synchronized (mLock) {
            WifiMetricsProto.WifiLog.ScanReturnEntry entry = mScanReturnEntries.get(scanReturnCode);
            if (entry == null) {
                entry = new WifiMetricsProto.WifiLog.ScanReturnEntry();
                entry.scanReturnCode = scanReturnCode;
                entry.scanResultsCount = 0;
            }
            entry.scanResultsCount++;
            mScanReturnEntries.put(scanReturnCode, entry);
        }
    }

    /**
     * Increments the count of scans initiated by each wifi state, accounts for screenOn/Off
     *
     * @param state State of the system when scan was initiated, see WifiMetricsProto.WifiLog.WIFI_X
     * @param screenOn Is the screen on
     */
    public void incrementWifiSystemScanStateCount(int state, boolean screenOn) {
        synchronized (mLock) {
            int index = state * (screenOn ? 2 : 1);
            WifiMetricsProto.WifiLog.WifiSystemStateEntry entry =
                    mWifiSystemStateEntries.get(index);
            if (entry == null) {
                entry = new WifiMetricsProto.WifiLog.WifiSystemStateEntry();
                entry.wifiState = state;
                entry.wifiStateCount = 0;
                entry.isScreenOn = screenOn;
            }
            entry.wifiStateCount++;
            mWifiSystemStateEntries.put(state, entry);
        }
    }

    public static final String PROTO_DUMP_ARG = "wifiMetricsProto";
    /**
     * Dump all WifiMetrics. Collects some metrics from ConfigStore, Settings and WifiManager
     * at this time
     *
     * @param fd unused
     * @param pw PrintWriter for writing dump to
     * @param args unused
     */
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        synchronized (mLock) {
            pw.println("WifiMetrics:");
            if (args.length > 0 && PROTO_DUMP_ARG.equals(args[0])) {
                //Dump serialized WifiLog proto
                consolidateProto(true);
                for (ConnectionEvent event : mConnectionEventList) {
                    if (mCurrentConnectionEvent != event) {
                        //indicate that automatic bug report has been taken for all valid
                        //connection events
                        event.mConnectionEvent.automaticBugReportTaken = true;
                    }
                }
                byte[] wifiMetricsProto = WifiMetricsProto.WifiLog.toByteArray(mWifiLogProto);
                String metricsProtoDump = Base64.encodeToString(wifiMetricsProto, Base64.DEFAULT);
                pw.println(metricsProtoDump);
                pw.println("EndWifiMetrics");
                clear();
            } else {
                pw.println("mConnectionEvents:");
                for (ConnectionEvent event : mConnectionEventList) {
                    String eventLine = event.toString();
                    if (event == mCurrentConnectionEvent) {
                        eventLine += "CURRENTLY OPEN EVENT";
                    }
                    pw.println(eventLine);
                }
                pw.println("mWifiLogProto.numSavedNetworks=" + mWifiLogProto.numSavedNetworks);
                pw.println("mWifiLogProto.numOpenNetworks=" + mWifiLogProto.numOpenNetworks);
                pw.println("mWifiLogProto.numPersonalNetworks="
                        + mWifiLogProto.numPersonalNetworks);
                pw.println("mWifiLogProto.numEnterpriseNetworks="
                        + mWifiLogProto.numEnterpriseNetworks);
                pw.println("mWifiLogProto.isLocationEnabled=" + mWifiLogProto.isLocationEnabled);
                pw.println("mWifiLogProto.isScanningAlwaysEnabled="
                        + mWifiLogProto.isScanningAlwaysEnabled);
                pw.println("mWifiLogProto.numWifiToggledViaSettings="
                        + mWifiLogProto.numWifiToggledViaSettings);
                pw.println("mWifiLogProto.numWifiToggledViaAirplane="
                        + mWifiLogProto.numWifiToggledViaAirplane);
                pw.println("mWifiLogProto.numNetworksAddedByUser="
                        + mWifiLogProto.numNetworksAddedByUser);
                //TODO - Pending scanning refactor
                pw.println("mWifiLogProto.numNetworksAddedByApps=" + "<TODO>");
                pw.println("mWifiLogProto.numNonEmptyScanResults=" + "<TODO>");
                pw.println("mWifiLogProto.numEmptyScanResults=" + "<TODO>");
                pw.println("mWifiLogProto.numOneshotScans=" + "<TODO>");
                pw.println("mWifiLogProto.numBackgroundScans=" + "<TODO>");
                pw.println("mScanReturnEntries:" + " <TODO>");
                pw.println("mSystemStateEntries:" + " <TODO>");
            }
        }
    }

    /**
     * Assign the separate ConnectionEvent, SystemStateEntry and ScanReturnCode lists to their
     * respective lists within mWifiLogProto, and clear the original lists managed here.
     *
     * @param incremental Only include ConnectionEvents created since last automatic bug report
     */
    private void consolidateProto(boolean incremental) {
        List<WifiMetricsProto.ConnectionEvent> events = new ArrayList<>();
        synchronized (mLock) {
            for (ConnectionEvent event : mConnectionEventList) {
                if (!incremental || ((mCurrentConnectionEvent != event)
                        && !event.mConnectionEvent.automaticBugReportTaken)) {
                    //Get all ConnectionEvents that haven not been dumped as a proto, also exclude
                    //the current active un-ended connection event
                    events.add(event.mConnectionEvent);
                    event.mConnectionEvent.automaticBugReportTaken = true;
                }
            }
            if (events.size() > 0) {
                mWifiLogProto.connectionEvent = events.toArray(mWifiLogProto.connectionEvent);
            }
            //<TODO> SystemStateEntry and ScanReturnCode list consolidation
        }
    }

    /**
     * Serializes all of WifiMetrics to WifiLog proto, and returns the byte array.
     * Does not count as taking an automatic bug report
     *
     * @return byte array of the deserialized & consolidated Proto
     */
    public byte[] toByteArray() {
        consolidateProto(false);
        return mWifiLogProto.toByteArray(mWifiLogProto);
    }

    /**
     * Clear all WifiMetrics, except for currentConnectionEvent.
     */
    private void clear() {
        synchronized (mLock) {
            mConnectionEventList.clear();
            if (mCurrentConnectionEvent != null) {
                mConnectionEventList.add(mCurrentConnectionEvent);
            }
            mScanReturnEntries.clear();
            mWifiSystemStateEntries.clear();
            mWifiLogProto.clear();
        }
    }
}