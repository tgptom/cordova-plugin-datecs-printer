package com.giorgiofellipe.datecsprinter;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.CordovaInterface;
import org.apache.cordova.CordovaWebView;
import org.apache.cordova.PluginResult;
import org.json.JSONObject;
import org.json.JSONArray;
import org.json.JSONException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.Exception;
import java.util.Hashtable;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.UUID;
import java.net.Socket;
import java.net.UnknownHostException;
import android.app.Application;
import android.app.Activity;
import android.app.ProgressDialog;
import android.widget.Toast;
import android.util.Log;
import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Bundle;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import android.util.Base64;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import com.datecs.api.BuildInfo;
import com.datecs.api.printer.ProtocolAdapter;

public class DatecsSDKWrapper {
    private static final String LOG_TAG = "BluetoothPrinter";
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final int RFCOMM_PRE_CONNECT_DELAY_MS = 50;
    private static final int RFCOMM_FALLBACK_DELAY_MS = 400;
    private static final int RFCOMM_RETRY_DELAY_MS = 700;
    private static final int RFCOMM_MAX_CONNECT_ATTEMPTS = 2;
    private Printer mPrinter;
    private ProtocolAdapter mProtocolAdapter;
    private BluetoothSocket mBluetoothSocket;
    private BluetoothSocket mConnectingBluetoothSocket;
    private boolean mRestart;
    private String mAddress;
    private CallbackContext mConnectCallbackContext;
    private ProgressDialog mDialog;
    private CordovaInterface mCordova;
    private CordovaWebView mWebView;
    private final Application app;
    private final Object mConnectionLock = new Object();
    private int mConnectionAttemptId;
    private CallbackContext mPendingConnectionCallbackContext;

    private static final class BluetoothConnectionResult {
        private final InputStream inputStream;
        private final OutputStream outputStream;

        private BluetoothConnectionResult(InputStream inputStream, OutputStream outputStream) {
            this.inputStream = inputStream;
            this.outputStream = outputStream;
        }
    }

    private static final class ConnectAbortedException extends Exception {
        private ConnectAbortedException() {
            super("Connection attempt was cancelled");
        }
    }

    /**
     * Interface de eventos da Impressora
     */
    private final ProtocolAdapter.PrinterListener mChannelListener = new ProtocolAdapter.PrinterListener() {
        @Override
        public void onPaperStateChanged(boolean hasNoPaper) {
            if (hasNoPaper) {
                sendStatusUpdate(true, false);
                showToast(DatecsUtil.getStringFromStringResource(app, "no_paper"));
            } else {
                sendStatusUpdate(true, true);
                showToast(DatecsUtil.getStringFromStringResource(app, "paper_ok"));
            }
        }

        @Override
        public void onThermalHeadStateChanged(boolean overheated) {
            if (overheated) {
                closeActiveConnections();
                sendStatusUpdate(false, false);
                showToast(DatecsUtil.getStringFromStringResource(app, "overheating"));
            }
        }

        @Override
        public void onBatteryStateChanged(boolean lowBattery) {
            sendStatusUpdate(true, true, lowBattery);
            showToast(DatecsUtil.getStringFromStringResource(app, "low_battery"));
        }
    };

    private Map<Integer, String> errorCode = new HashMap<Integer, String>();

    public DatecsSDKWrapper(CordovaInterface cordova) {
        mCordova = cordova;
        app = cordova.getActivity().getApplication();

        this.errorCode.put(1, DatecsUtil.getStringFromStringResource(app, "err_no_bt_adapter"));
        this.errorCode.put(2, DatecsUtil.getStringFromStringResource(app, "err_no_bt_device"));
        this.errorCode.put(3, DatecsUtil.getStringFromStringResource(app, "err_lines_number"));
        this.errorCode.put(4, DatecsUtil.getStringFromStringResource(app, "err_feed_paper"));
        this.errorCode.put(5, DatecsUtil.getStringFromStringResource(app, "err_print"));
        this.errorCode.put(6, DatecsUtil.getStringFromStringResource(app, "err_fetch_st"));
        this.errorCode.put(7, DatecsUtil.getStringFromStringResource(app, "err_fetch_tmp"));
        this.errorCode.put(8, DatecsUtil.getStringFromStringResource(app, "err_print_barcode"));
        this.errorCode.put(9, DatecsUtil.getStringFromStringResource(app, "err_print_test"));
        this.errorCode.put(10, DatecsUtil.getStringFromStringResource(app, "err_set_barcode"));
        this.errorCode.put(11, DatecsUtil.getStringFromStringResource(app, "err_print_img"));
        this.errorCode.put(12, DatecsUtil.getStringFromStringResource(app, "err_print_rect"));
        this.errorCode.put(13, DatecsUtil.getStringFromStringResource(app, "err_print_rect"));
        this.errorCode.put(14, DatecsUtil.getStringFromStringResource(app, "err_print_rect"));
        this.errorCode.put(15, DatecsUtil.getStringFromStringResource(app, "err_print_rect"));
        this.errorCode.put(16, DatecsUtil.getStringFromStringResource(app, "err_print_rect"));
        this.errorCode.put(17, DatecsUtil.getStringFromStringResource(app, "err_print_rect"));
        this.errorCode.put(18, DatecsUtil.getStringFromStringResource(app, "failed_to_connect"));
        this.errorCode.put(19, DatecsUtil.getStringFromStringResource(app, "err_bt_socket"));
        this.errorCode.put(20, DatecsUtil.getStringFromStringResource(app, "failed_to_initialize"));
        this.errorCode.put(21, DatecsUtil.getStringFromStringResource(app, "err_write"));
        this.errorCode.put(22, DatecsUtil.getStringFromStringResource(app, "err_print_qrcode"));
        this.errorCode.put(23, DatecsUtil.getStringFromStringResource(app, "err_bt_connect_permission"));
    }

    private JSONObject getErrorByCode(int code) {
        return this.getErrorByCode(code, null);
    }

    private JSONObject getErrorByCode(int code, Exception exception) {
        JSONObject json = new JSONObject();
        try {
            json.put("errorCode", code);
            json.put("message", errorCode.get(code));
            if (exception != null) {
                json.put("exception", exception.getMessage());
            }
        } catch (Exception e) {
            Log.e(LOG_TAG, e.getMessage());
            showToast(e.getMessage());
        }
        return json;
    }

    protected JSONObject getBluetoothPermissionDeniedError() {
        return this.getErrorByCode(23);
    }

    private boolean hasBluetoothPermission(String permission) {
        Activity activity = mCordova.getActivity();
        return activity != null && activity.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasBluetoothConnectPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return true;
        }

        return hasBluetoothPermission(Manifest.permission.BLUETOOTH_CONNECT);
    }

    private boolean hasBluetoothScanPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return true;
        }

        return hasBluetoothPermission(Manifest.permission.BLUETOOTH_SCAN);
    }

    private boolean ensureBluetoothConnectPermission(CallbackContext callbackContext) {
        if (hasBluetoothConnectPermission()) {
            return true;
        }

        callbackContext.error(this.getBluetoothPermissionDeniedError());
        return false;
    }

    private boolean ensureBluetoothConnectionPermissions(CallbackContext callbackContext) {
        if (hasBluetoothConnectPermission() && hasBluetoothScanPermission()) {
            return true;
        }

        callbackContext.error(this.getBluetoothPermissionDeniedError());
        return false;
    }

    private boolean cancelDiscoverySafely(BluetoothAdapter adapter, CallbackContext callbackContext) {
        if (adapter == null) {
            return false;
        }

        if (!hasBluetoothScanPermission()) {
            callbackContext.error(this.getBluetoothPermissionDeniedError());
            return false;
        }

        try {
            adapter.cancelDiscovery();
            return true;
        } catch (SecurityException exception) {
            Log.e(LOG_TAG, "Bluetooth permission denied while cancelling discovery", exception);
            callbackContext.error(this.getBluetoothPermissionDeniedError());
            return false;
        }
    }

    /**
     * Busca todos os dispositivos Bluetooth pareados com o device
     *
     * @param callbackContext
     */
    protected void getBluetoothPairedDevices(CallbackContext callbackContext) {
        BluetoothAdapter mBluetoothAdapter = null;
        try {
            if (!ensureBluetoothConnectPermission(callbackContext)) {
                return;
            }

            mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
            if (mBluetoothAdapter == null) {
                callbackContext.error(this.getErrorByCode(1));
                return;
            }
            if (!mBluetoothAdapter.isEnabled()) {
                // Must start the enable-Bluetooth activity from the UI thread
                mCordova.getActivity().runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Intent enableBluetooth = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
                        mCordova.getActivity().startActivityForResult(enableBluetooth, 0);
                    }
                });
                // Bluetooth is not yet enabled; the caller must retry once it is
                callbackContext.error(this.getErrorByCode(1));
                return;
            }
            Set<BluetoothDevice> pairedDevices = mBluetoothAdapter.getBondedDevices();
            if (pairedDevices.size() > 0) {
                JSONArray json = new JSONArray();
                for (BluetoothDevice device : pairedDevices) {
                    Hashtable map = new Hashtable();
                    int deviceType = device.getType();
                    map.put("type", deviceType);
                    map.put("address", device.getAddress());
                    map.put("name", device.getName());
                    String deviceAlias = device.getName();
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        try {
                            String alias = device.getAlias();
                            if (alias != null && !alias.isEmpty()) {
                                deviceAlias = alias;
                            }
                        } catch (SecurityException e) {
                            Log.w(LOG_TAG, "Bluetooth permission denied while getting device alias");
                        }
                    }
                    map.put("aliasName", deviceAlias);
                    JSONObject jObj = new JSONObject(map);
                    json.put(jObj);
                }
                callbackContext.success(json);
            } else {
                callbackContext.error(this.getErrorByCode(2));
            }
        } catch (Exception e) {
            Log.e(LOG_TAG, e.getMessage());
            e.printStackTrace();
            callbackContext.error(e.getMessage());
        }
    }

    /**
     * Seta em memória o endereço da impressora cuja conexão está sendo estabelecida
     *
     * @param address
     */
    protected void setAddress(String address) {
        mAddress = address;
    }

    protected void setWebView(CordovaWebView webView) {
        mWebView = webView;
    }

    // public void setCordova(CordovaInterface cordova) {
    //     mCordova = cordova;
    // }

    /**
     * Valida o endereço da impressora e efetua a conexão
     *
     * @param callbackContext
     */
    protected void connect(CallbackContext callbackContext) {
        if (!ensureBluetoothConnectionPermissions(callbackContext)) {
            return;
        }

        mConnectCallbackContext = callbackContext;
        closeActiveConnections();
        if (BluetoothAdapter.checkBluetoothAddress(mAddress)) {
            establishBluetoothConnection(mAddress, callbackContext, beginConnectionAttempt(callbackContext));
        }
    }

    /**
     * Encerra todas as conexões com impressoras e dispositivos Bluetooth ativas
     */
    public synchronized void closeActiveConnections() {
        CallbackContext cancelledCallbackContext = cancelPendingConnectionAttempt();
        closePrinterConnection();
        closeBluetoothConnection();
        if (cancelledCallbackContext != null) {
            cancelledCallbackContext.error(this.getErrorByCode(18));
        }
    }

    /**
     * Encerra a conexão com a impressora
     */
    private synchronized void closePrinterConnection() {
        if (mPrinter != null) {
            mPrinter.close();
            mPrinter = null;
        }

        if (mProtocolAdapter != null) {
            mProtocolAdapter.close();
            mProtocolAdapter = null;
        }
    }

    /**
     * Finaliza o socket Bluetooth e encerra todas as conexões
     */
    private synchronized void closeBluetoothConnection() {
        BluetoothSocket socket;
        BluetoothSocket connectingSocket;
        synchronized (mConnectionLock) {
            socket = mBluetoothSocket;
            connectingSocket = mConnectingBluetoothSocket;
            mBluetoothSocket = null;
            mConnectingBluetoothSocket = null;
        }

        closeSocketQuietly(socket);
        if (connectingSocket != socket) {
            closeSocketQuietly(connectingSocket);
        }
    }

    /**
     * Efetiva a conexão com o dispositivo Bluetooth
     *
     * @param address
     * @param callbackContext
     */
    private void establishBluetoothConnection(final String address, final CallbackContext callbackContext, final int connectionAttemptId) {
        final DatecsSDKWrapper sdk = this;
        runJob(new Runnable() {
            @Override
            public void run() {
                BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
                if (adapter == null) {
                    completeConnectionError(connectionAttemptId, callbackContext, sdk.getErrorByCode(1));
                    return;
                }

                try {
                    BluetoothDevice device = adapter.getRemoteDevice(address);
                    if (!cancelDiscoverySafely(adapter, callbackContext)) {
                        clearPendingConnectionAttempt(connectionAttemptId, callbackContext);
                        return;
                    }

                    try {
                        BluetoothConnectionResult connectionResult = connectBluetoothSocket(device, address, callbackContext, connectionAttemptId);
                        if (connectionResult == null) {
                            return;
                        }

                        try {
                            initializePrinter(connectionResult.inputStream, connectionResult.outputStream, connectionAttemptId);
                        } catch (IOException initializationException) {
                            initializationException.printStackTrace();
                            closePrinterConnection();
                            closeBluetoothConnection();
                            completeConnectionError(connectionAttemptId, callbackContext, sdk.getErrorByCode(20));
                            return;
                        }

                        if (completeConnectionSuccess(connectionAttemptId, callbackContext)) {
                            showToast(DatecsUtil.getStringFromStringResource(app, "printer_connected"));
                            sendStatusUpdate(true);
                        }
                        return;
                    } catch (SecurityException ex) {
                        Log.e(LOG_TAG, "Bluetooth permission denied while connecting", ex);
                        completeConnectionError(connectionAttemptId, callbackContext, sdk.getBluetoothPermissionDeniedError());
                        return;
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        closeActiveConnections();
                        completeConnectionError(connectionAttemptId, callbackContext, sdk.getErrorByCode(18, ex));
                        return;
                    } catch (ConnectAbortedException ex) {
                        return;
                    } catch (Exception ex) {
                        ex.printStackTrace();
                        completeConnectionError(connectionAttemptId, callbackContext, sdk.getErrorByCode(18, ex));
                        return;
                    }
                } catch (SecurityException e) {
                    Log.e(LOG_TAG, "Bluetooth permission denied while connecting", e);
                    completeConnectionError(connectionAttemptId, callbackContext, sdk.getBluetoothPermissionDeniedError());
                    return;
                } catch (Exception e) {
                    e.printStackTrace();
                    completeConnectionError(connectionAttemptId, callbackContext, sdk.getErrorByCode(18, e));
                    return;
                }
            }
        }, DatecsUtil.getStringFromStringResource(app, "printer"), DatecsUtil.getStringFromStringResource(app, "connecting"));
    }

    /**
     * Inicializa a troca de dados com a impressora
     * @param inputStream
     * @param outputStream
     * @param connectionAttemptId
     * @throws IOException
     */
    protected void initializePrinter(InputStream inputStream, OutputStream outputStream, int connectionAttemptId) throws IOException, ConnectAbortedException {
        ProtocolAdapter protocolAdapter = new ProtocolAdapter(inputStream, outputStream);
        Printer printer;
        if (protocolAdapter.isProtocolEnabled()) {
            protocolAdapter.setPrinterListener(mChannelListener);

            final ProtocolAdapter.Channel channel = protocolAdapter.getChannel(ProtocolAdapter.CHANNEL_PRINTER);

            printer = new Printer(channel.getInputStream(), channel.getOutputStream());
        } else {
            printer = new Printer(protocolAdapter.getRawInputStream(), protocolAdapter.getRawOutputStream());
        }

        printer.setConnectionListener(new Printer.ConnectionListener() {
            @Override
            public void onDisconnect() {
                sendStatusUpdate(false);
            }
        });

        synchronized (mConnectionLock) {
            if (!isConnectionAttemptActiveLocked(connectionAttemptId)) {
                printer.close();
                protocolAdapter.close();
                throw new ConnectAbortedException();
            }
            mProtocolAdapter = protocolAdapter;
            mPrinter = printer;
        }
    }

    private int beginConnectionAttempt(CallbackContext callbackContext) {
        synchronized (mConnectionLock) {
            mConnectionAttemptId++;
            mPendingConnectionCallbackContext = callbackContext;
            return mConnectionAttemptId;
        }
    }

    private CallbackContext cancelPendingConnectionAttempt() {
        synchronized (mConnectionLock) {
            CallbackContext callbackContext = mPendingConnectionCallbackContext;
            mConnectionAttemptId++;
            mPendingConnectionCallbackContext = null;
            return callbackContext;
        }
    }

    private void clearPendingConnectionAttempt(int connectionAttemptId, CallbackContext callbackContext) {
        synchronized (mConnectionLock) {
            if (isConnectionAttemptActiveForCallbackLocked(connectionAttemptId, callbackContext)) {
                mPendingConnectionCallbackContext = null;
            }
        }
    }

    private boolean completeConnectionSuccess(int connectionAttemptId, CallbackContext callbackContext) {
        synchronized (mConnectionLock) {
            if (!isConnectionAttemptActiveForCallbackLocked(connectionAttemptId, callbackContext)) {
                return false;
            }
            mPendingConnectionCallbackContext = null;
        }
        callbackContext.success();
        return true;
    }

    private boolean completeConnectionError(int connectionAttemptId, CallbackContext callbackContext, JSONObject error) {
        synchronized (mConnectionLock) {
            if (!isConnectionAttemptActiveForCallbackLocked(connectionAttemptId, callbackContext)) {
                return false;
            }
            mPendingConnectionCallbackContext = null;
        }
        callbackContext.error(error);
        return true;
    }

    private boolean isConnectionAttemptActive(int connectionAttemptId) {
        synchronized (mConnectionLock) {
            return isConnectionAttemptActiveLocked(connectionAttemptId);
        }
    }

    private boolean isConnectionAttemptActiveLocked(int connectionAttemptId) {
        return mConnectionAttemptId == connectionAttemptId && mPendingConnectionCallbackContext != null;
    }

    private boolean isConnectionAttemptActiveForCallbackLocked(int connectionAttemptId, CallbackContext callbackContext) {
        return isConnectionAttemptActiveLocked(connectionAttemptId) && mPendingConnectionCallbackContext == callbackContext;
    }

    private BluetoothConnectionResult connectBluetoothSocket(BluetoothDevice device, String address, CallbackContext callbackContext, int connectionAttemptId) throws IOException, InterruptedException, ConnectAbortedException {
        IOException lastFailure = null;

        for (int attempt = 0; attempt < RFCOMM_MAX_CONNECT_ATTEMPTS; attempt++) {
            if (attempt > 0) {
                waitForBluetoothStack(connectionAttemptId, RFCOMM_RETRY_DELAY_MS);
            }

            try {
                return openBluetoothSocket(device, connectionAttemptId, false);
            } catch (IOException secureFailure) {
                lastFailure = secureFailure;
                Log.w(LOG_TAG, "Secure RFCOMM connection failed for " + address + ", attempting insecure fallback", secureFailure);
            }

            // Android's Bluetooth stack may need a short pause after a failed RFCOMM close before reusing the slot.
            waitForBluetoothStack(connectionAttemptId, RFCOMM_FALLBACK_DELAY_MS);

            try {
                return openBluetoothSocket(device, connectionAttemptId, true);
            } catch (IOException insecureFailure) {
                lastFailure = insecureFailure;
                if (attempt + 1 >= RFCOMM_MAX_CONNECT_ATTEMPTS || !isTransientRfcommFailure(insecureFailure)) {
                    break;
                }
                Log.w(LOG_TAG, "Transient RFCOMM failure for " + address + ", retrying Bluetooth connection", insecureFailure);
            }
        }

        Log.e(LOG_TAG, "Both RFCOMM connection attempts failed for " + address, lastFailure);
        completeConnectionError(connectionAttemptId, callbackContext, getErrorByCode(18, lastFailure));
        return null;
    }

    private BluetoothConnectionResult openBluetoothSocket(BluetoothDevice device, int connectionAttemptId, boolean insecure) throws IOException, InterruptedException, ConnectAbortedException {
        BluetoothSocket candidateSocket = insecure
                ? device.createInsecureRfcommSocketToServiceRecord(SPP_UUID)
                : device.createRfcommSocketToServiceRecord(SPP_UUID);
        try {
            registerConnectingSocket(connectionAttemptId, candidateSocket);
            waitForBluetoothStack(connectionAttemptId, RFCOMM_PRE_CONNECT_DELAY_MS);
            candidateSocket.connect();
            InputStream inputStream = candidateSocket.getInputStream();
            OutputStream outputStream = candidateSocket.getOutputStream();
            promoteConnectedSocket(connectionAttemptId, candidateSocket);
            return new BluetoothConnectionResult(inputStream, outputStream);
        } catch (IOException e) {
            clearTrackedSocket(candidateSocket);
            closeSocketQuietly(candidateSocket);
            throw e;
        } catch (InterruptedException e) {
            clearTrackedSocket(candidateSocket);
            closeSocketQuietly(candidateSocket);
            throw e;
        } catch (ConnectAbortedException e) {
            clearTrackedSocket(candidateSocket);
            closeSocketQuietly(candidateSocket);
            throw e;
        } catch (RuntimeException e) {
            clearTrackedSocket(candidateSocket);
            closeSocketQuietly(candidateSocket);
            throw e;
        }
    }

    private void registerConnectingSocket(int connectionAttemptId, BluetoothSocket socket) throws ConnectAbortedException {
        synchronized (mConnectionLock) {
            if (!isConnectionAttemptActiveLocked(connectionAttemptId)) {
                throw new ConnectAbortedException();
            }
            mConnectingBluetoothSocket = socket;
        }
    }

    private void promoteConnectedSocket(int connectionAttemptId, BluetoothSocket socket) throws ConnectAbortedException {
        synchronized (mConnectionLock) {
            if (!isConnectionAttemptActiveLocked(connectionAttemptId)) {
                throw new ConnectAbortedException();
            }
            if (mConnectingBluetoothSocket == socket) {
                mConnectingBluetoothSocket = null;
            }
            mBluetoothSocket = socket;
        }
    }

    private void clearTrackedSocket(BluetoothSocket socket) {
        synchronized (mConnectionLock) {
            if (mConnectingBluetoothSocket == socket) {
                mConnectingBluetoothSocket = null;
            }
            if (mBluetoothSocket == socket) {
                mBluetoothSocket = null;
            }
        }
    }

    private void waitForBluetoothStack(int connectionAttemptId, int delayMs) throws InterruptedException, ConnectAbortedException {
        if (!isConnectionAttemptActive(connectionAttemptId)) {
            throw new ConnectAbortedException();
        }
        Thread.sleep(delayMs);
        if (!isConnectionAttemptActive(connectionAttemptId)) {
            throw new ConnectAbortedException();
        }
    }

    private boolean isTransientRfcommFailure(IOException exception) {
        String message = exception.getMessage();
        if (message == null) {
            return true;
        }

        String normalizedMessage = message.toLowerCase();
        return normalizedMessage.contains("read failed")
                || normalizedMessage.contains("socket might closed or timeout")
                || normalizedMessage.contains("connection reset")
                || normalizedMessage.contains("software caused connection abort")
                || normalizedMessage.contains("try again")
                || normalizedMessage.contains("busy");
    }

    private void closeSocketQuietly(BluetoothSocket socket) {
        if (socket == null) {
            return;
        }

        try {
            socket.close();
        } catch (IOException e) {
            Log.w(LOG_TAG, "Failed to close Bluetooth socket cleanly", e);
        }
    }

    /**
     * Alimenta papel à impressora (rola papel em branco)
     *
     * @param linesQuantity
     * @param callbackContext
     */
    public void feedPaper(int linesQuantity, CallbackContext callbackContext) {
        if (linesQuantity < 0 || linesQuantity > 255) {
            callbackContext.error(this.getErrorByCode(3));
        }
        try {
            mPrinter.feedPaper(linesQuantity);
            mPrinter.flush();
            callbackContext.success();
        } catch (Exception e) {
            e.printStackTrace();
            callbackContext.error(this.getErrorByCode(4, e));
        }
    }

    /**
     * Print text expecting markup formatting tags (default encoding is ISO-8859-1)
     *
     * @param text
     * @param callbackContext
     */
    public void printTaggedText(String text, CallbackContext callbackContext) {
        printTaggedText(text, "ISO-8859-1", callbackContext);
    }

    /**
     * Print text expecting markup formatting tags and a defined charset
     *
     * @param text
     * @param charset
     * @param callbackContext
     */
    public void printTaggedText(String text, String charset, CallbackContext callbackContext) {
        try {
            mPrinter.printTaggedText(text, charset);
            mPrinter.flush();
            callbackContext.success();
        } catch (Exception e) {
            e.printStackTrace();
            Log.e(LOG_TAG, e.getMessage());
            callbackContext.error(this.getErrorByCode(5, e));
        }
    }

    /**
     * Converts HEX String into byte array and write
     *
     * @param s
     * @param callbackContext
     */
    public void writeHex(String s, CallbackContext callbackContext) {
        write(DatecsUtil.hexStringToByteArray(s), callbackContext);
    }

    /**
     * Writes all bytes from the specified byte array to this printer
     *
     * @param b
     * @param callbackContext
     */
    public void write(byte[] b, CallbackContext callbackContext) {
        try {
            mPrinter.write(b);
            mPrinter.flush();
            callbackContext.success();
        } catch (Exception e) {
            e.printStackTrace();
            callbackContext.error(this.getErrorByCode(21, e));
        }
    }

    /**
     * Return what is the Printer current status
     *
     * @param callbackContext
     */
    public void getStatus(CallbackContext callbackContext) {
        try {
            int status = mPrinter.getStatus();
            callbackContext.success(status);
        } catch (Exception e) {
            e.printStackTrace();
            callbackContext.error(this.getErrorByCode(6, e));
        }
    }

    /**
     * Return Printer's head temperature
     *
     * @param callbackContext
     */
    public void getTemperature(CallbackContext callbackContext) {
        try {
            int temperature = mPrinter.getTemperature();
            callbackContext.success(temperature);
        } catch (Exception e) {
            e.printStackTrace();
            callbackContext.error(this.getErrorByCode(7, e));
        }
    }

    public void setBarcode(int align, boolean small, int scale, int hri, int height, CallbackContext callbackContext) {
        try {
            mPrinter.setBarcode(align, small, scale, hri, height);
            callbackContext.success();
        } catch (Exception e) {
            e.printStackTrace();
            callbackContext.error(this.getErrorByCode(10, e));
        }
    }

    /**
     * Print a Barcode
     *
     * @param type
     * @param data
     * @param callbackContext
     */
    public void printBarcode(int type, String data, CallbackContext callbackContext) {
        try {
            mPrinter.printBarcode(type, data);
            mPrinter.flush();
            callbackContext.success();
        } catch (Exception e) {
            e.printStackTrace();
            callbackContext.error(this.getErrorByCode(8, e));
        }
    }

    /**
     * Print a QRCode
     *
     * @param size - the size of symbol, value in {1, 4, 6, 8, 10, 12, 14}
     * @param eccLv - the error collection control level, where 1: L (7%), 2: M (15%), 3: Q (25%), 4: H (30%)
     * @param data - the QRCode data. The data must be between 1 and 448 symbols long.
     * @param callbackContext
     */
    public void printQRCode(int size, int eccLv, String data, CallbackContext callbackContext) {
        try {
            mPrinter.printQRCode(size, eccLv, data);
            mPrinter.flush();
            callbackContext.success();
        } catch (Exception e) {
            e.printStackTrace();
            callbackContext.error(this.getErrorByCode(22, e));
        }
    }


    /**
     * Print a selftest page
     *
     * @param callbackContext
     */
    public void printSelfTest(CallbackContext callbackContext) {
        try {
            mPrinter.printSelfTest();
            mPrinter.flush();
            callbackContext.success();
        } catch (Exception e) {
            e.printStackTrace();
            callbackContext.error(this.getErrorByCode(9, e));
        }
    }

    public void drawPageRectangle(int x, int y, int width, int height, int fillMode, CallbackContext callbackContext) {
        try {
            mPrinter.drawPageRectangle(x, y, width, height, fillMode);
            callbackContext.success();
        } catch (Exception e) {
            e.printStackTrace();
            callbackContext.error(this.getErrorByCode(12, e));
        }
    }

    public void drawPageFrame(int x, int y, int width, int height, int fillMode, int thickness, CallbackContext callbackContext) {
        try {
            mPrinter.drawPageFrame(x, y, width, height, fillMode, thickness);
            callbackContext.success();
        } catch (Exception e) {
            e.printStackTrace();
            callbackContext.error(this.getErrorByCode(16, e));
        }
    }

    public void selectStandardMode(CallbackContext callbackContext) {
        try {
            mPrinter.selectStandardMode();
            callbackContext.success();
        } catch (Exception e) {
            e.printStackTrace();
            callbackContext.error(this.getErrorByCode(13, e));
        }
    }

    public void selectPageMode(CallbackContext callbackContext) {
        try {
            mPrinter.selectPageMode();
            callbackContext.success();
        } catch (Exception e) {
            e.printStackTrace();
            callbackContext.error(this.getErrorByCode(14, e));
        }
    }

    public void printPage(CallbackContext callbackContext) {
        try {
            mPrinter.printPage();
            mPrinter.flush();
            callbackContext.success();
        } catch (Exception e) {
            e.printStackTrace();
            callbackContext.error(this.getErrorByCode(17, e));
        }
    }

    public void setPageRegion(int x, int y, int width, int height, int direction, CallbackContext callbackContext) {
        try {
            mPrinter.setPageRegion(x, y, width, height, direction);
            callbackContext.success();
        } catch (Exception e) {
            e.printStackTrace();
            callbackContext.error(this.getErrorByCode(15, e));
        }
    }


    /**
     * Print an image
     *
     * @param image String (BASE64 encoded image)
     * @param width
     * @param height
     * @param align
     * @param callbackContext
     */
    public void printImage(String image, int width, int height, int align, CallbackContext callbackContext) {
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inScaled = false;
            byte[] decodedByte = Base64.decode(image, 0);
            Bitmap bitmap = BitmapFactory.decodeByteArray(decodedByte, 0, decodedByte.length);
            final int imgWidth = bitmap.getWidth();
            final int imgHeight = bitmap.getHeight();
            final int[] argb = new int[imgWidth * imgHeight];

            bitmap.getPixels(argb, 0, imgWidth, 0, 0, imgWidth, imgHeight);
            bitmap.recycle();

            mPrinter.printImage(argb, width, height, align, true);
            mPrinter.flush();
            callbackContext.success();
        } catch (Exception e) {
            e.printStackTrace();
            callbackContext.error(this.getErrorByCode(11, e));
        }
    }

    /**
     * Wrapper para criação de Threads
     *
     * @param job
     * @param jobTitle
     * @param jobName
     */
    private void runJob(final Runnable job, final String jobTitle, final String jobName) {
        // Show progress dialog on the UI thread, then execute the job on Cordova's thread pool
        mCordova.getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                final ProgressDialog dialog = new ProgressDialog(mCordova.getActivity());
                dialog.setTitle(jobTitle);
                dialog.setMessage(jobName);
                dialog.setCancelable(false);
                dialog.setCanceledOnTouchOutside(false);
                dialog.show();

                mCordova.getThreadPool().execute(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            job.run();
                        } finally {
                            dialog.dismiss();
                        }
                    }
                });
            }
        });
    }

    /**
     * Exibe Toast de erro
     *
     * @param text
     * @param resetConnection
     */
    private void showError(final String text, boolean resetConnection) {
        //we'l ignore toasts at the moment
    //    mCordova.getActivity().runOnUiThread(new Runnable() {
    //        @Override
    //        public void run() {
    //            Toast.makeText(mCordova.getActivity().getApplicationContext(), text, Toast.LENGTH_SHORT).show();
    //        }
    //    });
        if (resetConnection) {
            connect(mConnectCallbackContext);
        }
    }

    /**
     * Exibe mensagem Toast
     *
     * @param text
     */
    private void showToast(final String text) {
        //we'l ignore toasts at the moment
//        mCordova.getActivity().runOnUiThread(new Runnable() {
//            @Override
//            public void run() {
//                if (!mCordova.getActivity().isFinishing()) {
//                    Toast.makeText(mCordova.getActivity().getApplicationContext(), text, Toast.LENGTH_SHORT).show();
//                }
//            }
//        });
    }

    /**
     * Create a new plugin result and send it back to JavaScript
     *
     * @param connection status
     */
    private void sendStatusUpdate(boolean isConnected, boolean hasPaper, boolean lowBattery) {
        final Intent intent = new Intent("DatecsPrinter.connectionStatus");

        Bundle b = new Bundle();
        b.putBoolean("isConnected", isConnected);
        b.putBoolean("hasPaper", hasPaper);
        b.putBoolean("lowBattery", lowBattery);
        intent.putExtras(b);

        LocalBroadcastManager.getInstance(mWebView.getContext()).sendBroadcastSync(intent);
    }
    
    private void sendStatusUpdate(boolean isConnected, boolean hasPaper) {
        this.sendStatusUpdate(isConnected, hasPaper, false);
    }

    private void sendStatusUpdate(boolean isConnected) {
        this.sendStatusUpdate(isConnected, true, false);
    }
}
