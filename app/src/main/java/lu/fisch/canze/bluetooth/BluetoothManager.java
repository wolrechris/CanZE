/**
 * Helper class to manage the Bluetooth connection
 */
package lu.fisch.canze.bluetooth;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.os.Build;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.security.InvalidParameterException;
import java.util.UUID;

import lu.fisch.canze.activities.MainActivity;
import lu.fisch.canze.interfaces.BluetoothEvent;

/**
 * Created by robertfisch on 03.09.2015.
 */
public class BluetoothManager {

    /* --------------------------------
     * Sigleton stuff
     \ ------------------------------ */

    private static BluetoothManager bluetoothManager = null;

    private InputStream inputStream     = null;
    private OutputStream outputStream   = null;

    public static BluetoothManager getInstance()
    {
        if(bluetoothManager ==null)
            bluetoothManager = new BluetoothManager();
        return bluetoothManager;
    }

    /* --------------------------------
     * Attributes
     \ ------------------------------ */
    // SPP UUID service
    private static final UUID MY_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    public static final int STATE_BLUETOOTH_NOT_AVAILABLE   = -1;
    public static final int STATE_BLUETOOTH_ACTIVE          = 1;
    public static final int STATE_BLUETOOTH_NOT_ACTIVE      = 0;


    private BluetoothAdapter bluetoothAdapter = null;
    private BluetoothSocket bluetoothSocket = null;

    private BluetoothEvent bluetoothEvent;

    public static final int RETRIES_NONE = 0;
    public static final int RETRIES_INFINITE = -1;
    private Thread retryThread = null;

    private String connectBluetoothAddress = null;
    private boolean connectSecure;
    private int connectRetries;
    private boolean retry = true;

    private void debug(String text)
    {
        MainActivity.debug(this.getClass().getSimpleName() + ": " + text);
    }

    /**
     * Create a new manager
     */
    private BluetoothManager()
    {
        // get Bluetooth adapter
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
    }

    /**
     * Determine the state of the Bluetooth hardware
     * @return  the state of the Bluetooth hardware
     */
    public int getHardwareState() {
        // Check for Bluetooth support and then check to make sure it is turned on
        // Emulator doesn't support Bluetooth and will return null
        if(bluetoothAdapter ==null)
        {
            return STATE_BLUETOOTH_NOT_AVAILABLE;
        }
        else
        {
            if (bluetoothAdapter.isEnabled())
            {
                return STATE_BLUETOOTH_ACTIVE;
            }
            else
            {
                return STATE_BLUETOOTH_NOT_ACTIVE;
            }
        }
    }

    /**
     * Creates a new Bluetooth socket from a given device
     * @param device
     * @return
     * @throws IOException
     */
    private BluetoothSocket createBluetoothSocket(BluetoothDevice device, boolean secure) throws IOException {
        if(Build.VERSION.SDK_INT >= 10){
            try {
                if(!secure) {
                    // insecure connection
                    final Method m = device.getClass().getMethod("createInsecureRfcommSocketToServiceRecord", new Class[]{UUID.class});
                    return (BluetoothSocket) m.invoke(device, MY_UUID);
                }
                else {
                    // secure connection
                    final Method m = device.getClass().getMethod("createRfcommSocketToServiceRecord", new Class[]{UUID.class});
                    return (BluetoothSocket) m.invoke(device, MY_UUID);
                }
            }
            catch (Exception e)
            {
                debug("Could not create RFComm Connection");
            }
        }
        return  device.createRfcommSocketToServiceRecord(MY_UUID);
    }

    public void connect()
    {
        if(connectBluetoothAddress==null) throw new InvalidParameterException("connect() has to be called at least once with parameters!");
        connect(connectBluetoothAddress, connectSecure, connectRetries);
    }

    public void connect(final String bluetoothAddress, final boolean secure, final int retries) {
        retry = true;
        privateConnect(bluetoothAddress, secure, retries);
    }

    private void privateConnect(final String bluetoothAddress, final boolean secure, final int retries)
    {
        if(retry) {
            // remember parameters
            connectBluetoothAddress = bluetoothAddress;
            connectSecure = secure;
            connectRetries = retries;

            // only continue if we got an address
            if (bluetoothAddress != null && !bluetoothAddress.isEmpty() && getHardwareState() == STATE_BLUETOOTH_ACTIVE) {

                // make sure there is no more active connection
                if (bluetoothSocket != null && !bluetoothSocket.isConnected()) {
                    try {
                        debug("Closing previous socket");
                        bluetoothSocket.close();
                        bluetoothSocket=null;
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }

                // execute attached event
                if (bluetoothEvent != null) bluetoothEvent.onBeforeConnect();

                // set up a pointer to the remote node using it's address.
                debug("Get remote device: " + bluetoothAddress);
                BluetoothDevice device = bluetoothAdapter.getRemoteDevice(bluetoothAddress);

                // create a socket
                try {
                    debug("Create new socket");
                    bluetoothSocket = createBluetoothSocket(device, secure);
                } catch (IOException e) {
                    e.printStackTrace();
                }

                // discovery is resource intensive so make sure it is stopped
                debug("Cancel discovery");
                bluetoothAdapter.cancelDiscovery();

                try {
                    debug("Connect the socket");
                    bluetoothSocket.connect();

                    debug("Connect the streams");
                    // connect the streams
                    try {
                        inputStream  = bluetoothSocket.getInputStream();
                        outputStream = bluetoothSocket.getOutputStream();
                    }
                    catch (IOException e) {
                        inputStream  = null;
                        outputStream = null;
                    }

                    // execute attached event
                    if (bluetoothEvent != null)
                        bluetoothEvent.onAfterConnect(bluetoothSocket);

                    debug("Connected");
                    return;
                } catch (IOException e) {
                    //e.printStackTrace();
                }
            }
            // if we reach this line, something went wrong and no connection has been established
            debug("Something went wrong");
            if (bluetoothAddress == null || bluetoothAddress.isEmpty())
                debug("No device address given");
            if (getHardwareState() == STATE_BLUETOOTH_NOT_ACTIVE)
                debug("Bluetooth not active");

            debug(retries + " tries left");
            if (retries != RETRIES_NONE) {
                if (retryThread == null || (retryThread != null && !retryThread.isAlive())) {
                    if(retryThread!=null)
                    {
                        retryThread.interrupt();
                    }
                    debug("Starting new try");
                    retryThread = new Thread(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                Thread.sleep(2 * 1000);
                            } catch (Exception e) {
                            }
                            (new Thread(new Runnable() {
                                @Override
                                public void run() {
                                    BluetoothManager.this.getInstance().privateConnect(bluetoothAddress, secure, retries - 1);
                                }
                            })).start();
                        }
                    });
                    retryThread.start();
                } else {
                    debug("Another try is still running --> abort this one");
                    debug("Alive: " + retryThread.isAlive());
                }
            }
        }
    }

    public void disconnect()
    {
        try {
            // execute attached event
            if(bluetoothEvent!=null) bluetoothEvent.onBeforeDisconnect(bluetoothSocket);

            retry=false;

            if(retryThread!=null && retryThread.isAlive()) {
                debug("Waiting for retry-thread to stop ...");
                retryThread.join();
            }

            debug("Closing socket");
            // close the socket
            if (bluetoothSocket != null)
                bluetoothSocket.close();

            // execute attached event
            if(bluetoothEvent!=null) bluetoothEvent.onAfterDisconnect();

            debug("Closed");
        }
        catch (IOException e)
        {
            e.printStackTrace();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    /* --------------------------------
     * input / output
     \ ------------------------------ */

    // write a message to the output stream
    public void write(String message) {
        if(bluetoothSocket.isConnected()) {
            byte[] msgBuffer = message.getBytes();
            try {
                outputStream.write(msgBuffer);
            } catch (IOException e) {
                Log.d(MainActivity.TAG, "BT: Error sending > " + e.getMessage());
                Log.d(MainActivity.TAG, "BT: Error sending > restaring BT");

                (new Thread(new Runnable() {
                    @Override
                    public void run() {
                        disconnect();
                        connect(connectBluetoothAddress, true, BluetoothManager.RETRIES_INFINITE);
                    }
                })).start();
            }
        }
        else MainActivity.debug("Write failed! Socket is closed ... M = "+message);
    }

    public int read(byte[] buffer) throws IOException {
        if(bluetoothSocket.isConnected())
            return inputStream.read(buffer);
        else
            return 0;
    }

    public int read() throws IOException {
        if(bluetoothSocket.isConnected())
            return inputStream.read();
        else
            return -1;
    }

    public int available() throws IOException {
        if(bluetoothSocket.isConnected())
            return inputStream.available();
        else
            return 0;
    }

    public boolean isConnected()
    {
        if(bluetoothSocket==null) return false;
        return bluetoothSocket.isConnected();
    }

    /* --------------------------------
     * Events
     \ ------------------------------ */

    public void setBluetoothEvent(BluetoothEvent bluetoothEvent) {
        this.bluetoothEvent = bluetoothEvent;
    }
}
