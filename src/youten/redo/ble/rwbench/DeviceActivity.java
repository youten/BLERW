/*
 * Copyright (C) 2014 youten
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

package youten.redo.ble.rwbench;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import youten.redo.ble.util.BleUtil;
import youten.redo.ble.util.BleUuid;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * BLEデバイスへのconnect・Service Discoveryを実施し、Characteristicsのread/writeをハンドリングするActivity
 */
public class DeviceActivity extends Activity implements View.OnClickListener {
    private static final String TAG = "BLEDevice";
    private static final int MAX_LAP = 99;

    public static final String EXTRA_BLUETOOTH_DEVICE = "BT_DEVICE";
    private BluetoothAdapter mBTAdapter;
    private BluetoothDevice mDevice;
    private BluetoothGatt mConnGatt;
    private BluetoothGattCharacteristic mManufacturererNameCh;
    private BluetoothGattCharacteristic mAlertLevelCh;
    private int mStatus;
    private List<Long> mLapList = new ArrayList<Long>();
    private int mReadCount = 0;
    private int mWriteCount = 0;

    private Button mStartBenchButton;
    private TextView mResultText;

    private final BluetoothGattCallback mGattcallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status,
                int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                addLog("Connected");
                mStatus = newState;
                // next state -> connected (discovering services)
                mConnGatt.discoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                addLog("Disconnected");
                mStatus = newState;
                mLapList.add(System.currentTimeMillis());
                int lap = mLapList.size();
                long delta = mLapList.get(lap - 1) - mLapList.get(0);
                addLog("lap " + lap + ":" + String.format("%10.3f", delta / 1000.0f));
                if (lap > MAX_LAP) {
                    finishBench(false);
                } else {
                    mManufacturererNameCh = null;
                    mAlertLevelCh = null;
                    if (mConnGatt != null) {
                        mConnGatt.close();
                        mConnGatt = null;
                    }
                    mStatus = BluetoothProfile.STATE_DISCONNECTED;
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException e) {
                        // ignore;
                    }
                    // next state -> connecting
                    connect();
                }
            }
        };

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            addLog("Services Discovered");
            for (BluetoothGattService service : gatt.getServices()) {
                if ((service == null) || (service.getUuid() == null)) {
                    continue;
                }
                if (BleUuid.SERVICE_DEVICE_INFORMATION.equalsIgnoreCase(service
                        .getUuid().toString())) {
                    mManufacturererNameCh = service.getCharacteristic(UUID
                            .fromString(BleUuid.CHAR_MANUFACTURER_NAME_STRING));
                }
                if (BleUuid.SERVICE_IMMEDIATE_ALERT.equalsIgnoreCase(service
                        .getUuid().toString())) {
                    mAlertLevelCh = service.getCharacteristic(UUID
                            .fromString(BleUuid.CHAR_ALERT_LEVEL));
                }
            }
            if ((mManufacturererNameCh != null) && (mAlertLevelCh != null) && (mConnGatt != null)) {
                addLog("Services Discovered");
                // next state -> reading
                if (!mConnGatt.readCharacteristic(mManufacturererNameCh)) {
                    addLog("readCharacteristic return false");
                    finishBench(true);
                    return;
                }
            }
        };

        @Override
        public void onCharacteristicRead(BluetoothGatt gatt,
                BluetoothGattCharacteristic characteristic, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                addLog("Read Succeeded");
                mReadCount++;
                if (BleUuid.CHAR_MANUFACTURER_NAME_STRING
                        .equalsIgnoreCase(characteristic.getUuid().toString())) {
                    final String name = characteristic.getStringValue(0);
                    Log.d(TAG, "read manufacturer name=" + name);

                    // next state -> writing Characteristic
                    if ((mConnGatt != null) && (mAlertLevelCh != null)) {
                        mAlertLevelCh.setValue(new byte[] {
                                (byte) mReadCount
                        });
                        if (!mConnGatt.writeCharacteristic(mAlertLevelCh)) {
                            addLog("writeCharacteristic return false");
                            finishBench(true);
                            return;
                        }
                    } else {
                        finishBench(true);
                        return;
                    }
                }
            } else {
                addLog("Read Failed");
                finishBench(true);
                return;
            }
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt,
                BluetoothGattCharacteristic characteristic, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                addLog("Write Succeeded");
                mWriteCount++;
            } else {
                addLog("Write Failed");
                finishBench(true);
                return;
            }

            // next state -> disconnect
            mConnGatt.disconnect();
        };
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_INDETERMINATE_PROGRESS);
        setContentView(R.layout.activity_device);

        // state
        mStatus = BluetoothProfile.STATE_DISCONNECTED;
        mStartBenchButton = (Button) findViewById(R.id.start_rwbench);
        mStartBenchButton.setOnClickListener(this);
        mResultText = (TextView) findViewById(R.id.bench_result);
    }

    @Override
    protected void onResume() {
        super.onResume();
        init();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        reset();
    }

    @Override
    public void onClick(View v) {
        if (v.getId() == R.id.start_rwbench) {
            setProgressBarIndeterminateVisibility(true);
            v.setEnabled(false);

            // clear and start bench
            reset();
            mLapList.add(System.currentTimeMillis());
            mResultText.setText("start:\n");
            connect();
        }
    }

    private void addLog(final String log) {
        if (log == null) {
            return;
        }
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (mResultText != null) {
                    mResultText.setText(mResultText.getText().toString() + log + "\n");
                    final ScrollView scrollView = (ScrollView) findViewById(R.id.result_scroll);
                    scrollView.post(new Runnable() {
                        @Override
                        public void run() {
                            scrollView.fullScroll(ScrollView.FOCUS_DOWN);
                        }
                    });
                }
            }
        });
    }

    private void connect() {
        // connect to Gatt
        if ((mConnGatt == null)
                && (mStatus == BluetoothProfile.STATE_DISCONNECTED)) {
            // try to connect
            mConnGatt = mDevice.connectGatt(this, false, mGattcallback);
            mStatus = BluetoothProfile.STATE_CONNECTING;
        } else {
            if (mConnGatt != null) {
                // re-connect and re-discover Services
                mConnGatt.connect();
                mConnGatt.discoverServices();
            } else {
                Log.e(TAG, "state error");
                finish();
                return;
            }
        }
    }

    private void init() {
        // BLE check
        if (!BleUtil.isBLESupported(this)) {
            Toast.makeText(this, R.string.ble_not_supported, Toast.LENGTH_SHORT)
                    .show();
            finish();
            return;
        }

        // BT check
        BluetoothManager manager = BleUtil.getManager(this);
        if (manager != null) {
            mBTAdapter = manager.getAdapter();
        }
        if (mBTAdapter == null) {
            Toast.makeText(this, R.string.bt_unavailable, Toast.LENGTH_SHORT)
                    .show();
            finish();
            return;
        }

        // check BluetoothDevice
        if (mDevice == null) {
            mDevice = getBTDeviceExtra();
            if (mDevice == null) {
                finish();
                return;
            }
        }
    }

    private void reset() {
        if (mConnGatt != null) {
            if ((mStatus != BluetoothProfile.STATE_DISCONNECTING)
                    && (mStatus != BluetoothProfile.STATE_DISCONNECTED)) {
                mConnGatt.disconnect();
            }
            mConnGatt.close();
            mConnGatt = null;
        }
        mStatus = BluetoothProfile.STATE_DISCONNECTED;
        mManufacturererNameCh = null;
        mAlertLevelCh = null;
        mLapList.clear();
        mReadCount = 0;
        mWriteCount = 0;
    }

    private void finishBench(boolean isError) {
        if (isError) {
            addLog("Error occured!");
        } else {
            addLog("Finish successfully! ReadCount:" + mReadCount + " WriteCount:" + mWriteCount);
        }

        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                setProgressBarIndeterminateVisibility(false);
                mStartBenchButton.setEnabled(true);
            }
        });
    }

    private BluetoothDevice getBTDeviceExtra() {
        Intent intent = getIntent();
        if (intent == null) {
            return null;
        }

        Bundle extras = intent.getExtras();
        if (extras == null) {
            return null;
        }

        return extras.getParcelable(EXTRA_BLUETOOTH_DEVICE);
    }

}
