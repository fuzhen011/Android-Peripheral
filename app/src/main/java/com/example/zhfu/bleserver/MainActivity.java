package com.example.zhfu.bleserver;

import android.Manifest;
import android.annotation.TargetApi;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.ParcelUuid;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

@TargetApi(Build.VERSION_CODES.LOLLIPOP)
public class MainActivity extends AppCompatActivity implements View.OnClickListener {

    private static final String TAG = "MainActivity";
    private static final String DEMO_SERVICE_UUID = "df6a8b89-32d1-486d-943a-1a1f6b0b52ed";
    private static final String NOTIFY_CHAR_UUID = "0ced7930-b31f-457d-a6a2-b3db9b03e39a";
    private static final String RW_CHAR_UUID = "fb958909-f26e-43a9-927c-7e17d8fb2d8d";
    private static final String CCC_UUID = "00002902-0000-1000-8000-00805f9b34fb";

    private static final int DISCONNECTED_STATE = 0;
    private static final int ADVERTISING_STATE = 1;
    private static final int NOTIFY_DISABLED = 2;
    private static final int NOTIFY_ENABLED_WITN_ONE_INTERVAL = 3;
    private static final int NOTIFY_ENABLED_WITN_FIVE_INTERVAL = 4;

    private AdvertiseData advData;
    private AdvertiseSettings advSettings;
    private BluetoothLeAdvertiser bleAdvertiser;
    private BluetoothGattServer bleServer;
    private BluetoothDevice remoteDevice;

    private boolean connected = false;

    private BluetoothGattCharacteristic notifyChar, rwChar;
    private byte[] cccValue;
    private byte rwCharValue;
    private boolean notifyStarted = false;

    private boolean appClosing = false;

    private Handler handler;
    private TextView stateText;

    private RadioGroup deviceStateGroup, notifyCharStateGroup, notifyIntervalGroup;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Log.i(TAG, "onCreate: ");

        if(!getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            Toast.makeText(this, R.string.ble_not_supported, Toast.LENGTH_SHORT).show();
            finish();
        }

        final BluetoothManager bleMan = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter bleAdpt = bleMan.getAdapter();
        if(!bleAdpt.isEnabled()) {
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableBtIntent, 1);
        } else {
            initAndGetServerState();
        }

        handler = new Handler();

        Button btn = (Button) findViewById(R.id.btn);
        btn.setOnClickListener(this);
        stateText = (TextView) findViewById(R.id.textview);

        deviceStateGroup = (RadioGroup) findViewById(R.id.state_radio_group);
        notifyCharStateGroup = (RadioGroup) findViewById(R.id.noti_radio_group);
        notifyIntervalGroup = (RadioGroup) findViewById(R.id.interval_radio_group);
    }

    @Override
    protected void onStart() {
        super.onStart();
        Log.i(TAG, "onStart: ");
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.i(TAG, "onResume: ");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.i(TAG, "onDestroy: ");

        if(bleAdvertiser != null) {
            bleAdvertiser.stopAdvertising(myAdvCallback);
            Log.i(TAG, "onDestroy: Stop adv...");
            bleAdvertiser = null;
        }

        if(remoteDevice != null) {
            bleServer.cancelConnection(remoteDevice);
            remoteDevice = null;
            appClosing = true;
//            bleServer.close();
//            bleServer = null;
        }
    }

    private void stateChangeUICallback(final int state) {
        if(state < DISCONNECTED_STATE || state > NOTIFY_ENABLED_WITN_FIVE_INTERVAL)
            return;
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if(state == DISCONNECTED_STATE) {
                    deviceStateGroup.clearCheck();
                } else if(state == ADVERTISING_STATE) {
                    deviceStateGroup.check(R.id.adv_check);
                } else {
                    deviceStateGroup.check(R.id.conn_check);
                }

                if(state < NOTIFY_DISABLED) {
                    notifyCharStateGroup.clearCheck();
                } else if(state == NOTIFY_DISABLED) {
                    notifyCharStateGroup.check(R.id.no_dis_check);
                } else {
                    notifyCharStateGroup.check(R.id.not_en_check);
                }

                if(state < NOTIFY_ENABLED_WITN_ONE_INTERVAL) {
                    notifyIntervalGroup.clearCheck();
                } else if(state == NOTIFY_ENABLED_WITN_ONE_INTERVAL) {
                    notifyIntervalGroup.check(R.id.one_s_check);
                } else {
                    notifyIntervalGroup.check(R.id.five_s_check);
                }
            }
        });
    }

    @Override
    protected void onStop() {
        super.onStop();
        Log.i(TAG, "onStop: ");
    }

    @Override
    protected void onPause() {
        super.onPause();
        Log.i(TAG, "onPause: ");
    }

    private void initAndGetServerState() {
        final BluetoothManager bleMan = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
//        BluetoothAdapter bleAdpt = bleMan.getAdapter();
        bleServer = bleMan.openGattServer(MainActivity.this, myServerCallback);
        bleServer.clearServices();
        initServer();
        initAdv();
        Handler advHandler = new Handler();
        advHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                synchronized(this) {
                    if(!connected && remoteDevice == null) {
                        Log.i(TAG, "500ms expired");
                        if(bleAdvertiser == null) {
                            bleAdvertiser = ((BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE)).getAdapter().getBluetoothLeAdvertiser();
                        }
                        bleAdvertiser.startAdvertising(advSettings, advData, myAdvCallback);
                    }
                }
            }
        }, 500);
    }

    private void initServer() {
        BluetoothGattService demoService = new BluetoothGattService(UUID.fromString(DEMO_SERVICE_UUID), BluetoothGattService.SERVICE_TYPE_PRIMARY);
        notifyChar = new BluetoothGattCharacteristic(UUID.fromString(NOTIFY_CHAR_UUID), BluetoothGattCharacteristic.PROPERTY_NOTIFY, 0);
        rwChar = new BluetoothGattCharacteristic(UUID.fromString(RW_CHAR_UUID), BluetoothGattCharacteristic.PROPERTY_WRITE | BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ | BluetoothGattCharacteristic.PERMISSION_WRITE);
        BluetoothGattDescriptor cccDescriptor = new BluetoothGattDescriptor(UUID.fromString(CCC_UUID), BluetoothGattDescriptor.PERMISSION_READ | BluetoothGattDescriptor.PERMISSION_WRITE);
        cccValue = new byte[2];
        rwCharValue = 0;
        cccValue = BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE;
        cccDescriptor.setValue(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE);
        notifyChar.addDescriptor(cccDescriptor);
        demoService.addCharacteristic(notifyChar);
        demoService.addCharacteristic(rwChar);
        bleServer.addService(demoService);
    }

    private void initAdv() {
        advData = new AdvertiseData.Builder()
                .addServiceUuid(new ParcelUuid(UUID.fromString(DEMO_SERVICE_UUID)))
                .build();
        bleAdvertiser = ((BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE)).getAdapter().getBluetoothLeAdvertiser();
        advSettings = new AdvertiseSettings.Builder().build();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if(requestCode == 1) {
            if(resultCode == 0) {
                Toast.makeText(MainActivity.this, "BLE Not Enabled", Toast.LENGTH_SHORT).show();
                finish();
            } else {
                initAndGetServerState();
            }
        }
    }

    public final BluetoothGattServerCallback myServerCallback = new BluetoothGattServerCallback() {
        @Override
        public void onServiceAdded(int status, BluetoothGattService service) {
//            super.onServiceAdded(status, service);
            Log.i(TAG, "onServiceAdded: success");
        }

        @Override
        public void onConnectionStateChange(final BluetoothDevice device, final int status, int newState) {
//            super.onConnectionStateChange(device, status, newState);
            if(newState == BluetoothProfile.STATE_DISCONNECTED) {
                synchronized(this) {
                    connected = false;
                    remoteDevice = null;
                }
                stateChangeUICallback(DISCONNECTED_STATE);
                startPeriodicalNotify(false);
                Log.i(TAG, "Connection State ---> Disconnected, Reason = " + status);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if(appClosing) {
                            bleServer.close();
                            Log.i(TAG, "Disconnected, Close BLE Server");
                        } else {
                            if(bleAdvertiser == null) {
                                bleAdvertiser = ((BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE)).getAdapter().getBluetoothLeAdvertiser();
                            }
                            bleAdvertiser.startAdvertising(advSettings, advData, myAdvCallback);
                        }
                        stateText.setText("Disconnected, reason = " + status + ". Advertising...");
                    }
                });
            } else if(newState == BluetoothProfile.STATE_CONNECTED) {
                synchronized(this) {
                    connected = true;
                    remoteDevice = device;
                }
                BluetoothDevice mDevice = BluetoothAdapter.getDefaultAdapter().getRemoteDevice(device.getAddress());
                boolean conn = bleServer.connect(mDevice, false);
                stateChangeUICallback(NOTIFY_DISABLED);
                Log.i(TAG, "Connection State ---> Connected, Gatt connected result = "+conn);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        stateText.setText(R.string.connected);
                        bleAdvertiser.stopAdvertising(myAdvCallback);
                    }
                });
            }
        }

        @Override
        public void onCharacteristicReadRequest(BluetoothDevice device, int requestId, int offset, BluetoothGattCharacteristic characteristic) {
//            super.onCharacteristicReadRequest(device, requestId, offset, characteristic);
            if(characteristic.getUuid().toString().equals(rwChar.getUuid().toString())) {
                bleServer.sendResponse(device, requestId, 0, 0, new byte[]{rwCharValue});
                Log.i(TAG, "Read RW characteristic");
            }
        }

        @Override
        public void onCharacteristicWriteRequest(BluetoothDevice device, int requestId, BluetoothGattCharacteristic characteristic, boolean preparedWrite, boolean responseNeeded, int offset, byte[] value) {
//            super.onCharacteristicWriteRequest(device, requestId, characteristic, preparedWrite, responseNeeded, offset, value);
            if(characteristic.getUuid().toString().equals(rwChar.getUuid().toString()) && !preparedWrite && responseNeeded) {
                bleServer.sendResponse(device, requestId, 0, 0, new byte[]{0});
                synchronized(this) {
                    rwCharValue = value[0];
                    if(rwCharValue == 5) {
                        stateChangeUICallback(NOTIFY_ENABLED_WITN_FIVE_INTERVAL);
                        Log.i(TAG, "Write 5 to RW characteristic");
                    } else {
                        stateChangeUICallback(NOTIFY_ENABLED_WITN_ONE_INTERVAL);
                        Log.i(TAG, "Write 1 to RW characteristic");
                    }
                }
            }
        }

        @Override
        public void onDescriptorReadRequest(BluetoothDevice device, int requestId, int offset, BluetoothGattDescriptor descriptor) {
//            super.onDescriptorReadRequest(device, requestId, offset, descriptor);
            if(descriptor.getCharacteristic().getUuid().toString().equals(notifyChar.getUuid().toString()) && descriptor.getUuid().toString().equals(CCC_UUID)) {
                if(remoteDevice != null) {
                    bleServer.sendResponse(remoteDevice, requestId, BluetoothGatt.GATT_SUCCESS, 0, cccValue);
                    Log.i(TAG, "Read CCC value");
                }
            }
        }

        @Override
        public void onDescriptorWriteRequest(BluetoothDevice device, int requestId, BluetoothGattDescriptor descriptor, boolean preparedWrite, boolean responseNeeded, int offset, byte[] value) {
//            super.onDescriptorWriteRequest(device, requestId, descriptor, preparedWrite, responseNeeded, offset, value);
            if(descriptor.getCharacteristic().getUuid().toString().equals(notifyChar.getUuid().toString()) && descriptor.getUuid().toString().equals(CCC_UUID)) {
                if(remoteDevice != null) {
                    bleServer.sendResponse(remoteDevice, requestId, BluetoothGatt.GATT_SUCCESS, 0, new byte[]{0});
                    Log.i(TAG, "Write CCC value");
                }

                System.arraycopy(value, 0, cccValue, 0, 2);
                if(Arrays.equals(cccValue, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) {
                    if(rwCharValue == 5) {
                        stateChangeUICallback(NOTIFY_ENABLED_WITN_FIVE_INTERVAL);
                    } else {
                        stateChangeUICallback(NOTIFY_ENABLED_WITN_ONE_INTERVAL);
                    }
                    startPeriodicalNotify(true);
                } else if(Arrays.equals(cccValue, BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)) {
                    stateChangeUICallback(NOTIFY_DISABLED);
                    startPeriodicalNotify(false);
                }
            }
        }

        @Override
        public void onNotificationSent(BluetoothDevice device, int status) {
            super.onNotificationSent(device, status);
        }
    };

    private void startPeriodicalNotify(boolean enable) {
        Runnable runable = new Runnable() {
            @Override
            public void run() {
                if(notifyStarted) {
                    if(rwCharValue == 0) {
                        handler.postDelayed(this, 1000);
                    } else {
                        handler.postDelayed(this, rwCharValue * 1000);
                    }
                }

                notifyChar.setValue(new byte[]{0x11, 0x22});
                if(remoteDevice != null) {
                    boolean ret = bleServer.notifyCharacteristicChanged(remoteDevice, notifyChar, false);
                }
            }
        };

        if(enable) {
            if(!notifyStarted) {
                // Not started
                Log.i(TAG, "Notification: Start.");
                notifyStarted = true;
                handler.postDelayed(runable, 50);
            }
        } else {
            Log.i(TAG, "Notification: Stop");
            notifyStarted = false;
            handler.removeCallbacks(runable);
        }
    }

    public final AdvertiseCallback myAdvCallback = new AdvertiseCallback() {
        @Override
        public void onStartSuccess(AdvertiseSettings settingsInEffect) {
            super.onStartSuccess(settingsInEffect);
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    stateText.setText(R.string.adving);
                }
            });
            stateChangeUICallback(ADVERTISING_STATE);
            Log.i(TAG, "Advertising... successfully");
        }

        @Override
        public void onStartFailure(final int errorCode) {
            super.onStartFailure(errorCode);
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    switch(errorCode) {
                        case AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED:
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    stateText.setText(R.string.adving);
                                }
                            });
                            stateChangeUICallback(ADVERTISING_STATE);
                            Log.i(TAG, "Advertising... Already started.");
                            break;
                        default:
                            Log.e(TAG, "Failed to start advertising---> error code = " + errorCode);
                            Toast.makeText(MainActivity.this, "Start Adv failed", Toast.LENGTH_SHORT).show();
                            break;
                    }
                }
            });
        }
    };

    @Override
    public void onClick(View v) {

//        notifyChar.setValue(new byte[]{0x11, 0x22});
//        boolean ret = bleServer.notifyCharacteristicChanged(remoteDevice, notifyChar, false);
//        Log.i(TAG, "onClick: ret = " + String.valueOf(ret));

        final BluetoothManager bleman = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        List<BluetoothDevice> devices = bleman.getConnectedDevices(BluetoothProfile.GATT_SERVER);
        BluetoothDevice device;
        if(!devices.isEmpty()) {
            Iterator<BluetoothDevice> iterator = devices.iterator();
            while(iterator.hasNext()) {
                device = iterator.next();
                Log.i(TAG, "Disconnect from: " + device.getAddress());
                bleServer.cancelConnection(device);
            }
        }

//        bleServer.close();

//        final BluetoothManager bleman = (BluetoothManager)getSystemService(Context.BLUETOOTH_SERVICE);
//        List<BluetoothDevice> devices = bleman.getConnectedDevices(BluetoothProfile.GATT_SERVER);
//        BluetoothDevice device;
//        if(!devices.isEmpty()){
//            Iterator<BluetoothDevice> iterator = devices.iterator();
//            while(iterator.hasNext()){
//                device = iterator.next();
//                Log.i(TAG, "Disconnect from: "+ device.getAddress());
//            }
//        }
    }
}
