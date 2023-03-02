package it.innove;


import static com.facebook.react.bridge.UiThreadUtil.runOnUiThread;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.ParcelUuid;
import android.util.Log;

import androidx.annotation.RequiresApi;
import androidx.core.app.ActivityCompat;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Callback;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReadableArray;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.WritableMap;

import java.util.ArrayList;
import java.util.List;

@RequiresApi(Build.VERSION_CODES.LOLLIPOP)
public class LollipopScanManager extends ScanManager {

    public LollipopScanManager(ReactApplicationContext reactContext, BleManager bleManager) {
        super(reactContext, bleManager);
    }

    @Override
    public void stopScan(Callback callback) {
        // update scanSessionId to prevent stopping next scan by running timeout thread
        scanSessionId.incrementAndGet();

        // TODO MGA: writeCharacteristic() may throw exception if user does not grant permissions !! introduce checks in lib instead of relying on client apps guards
        getBluetoothAdapter().getBluetoothLeScanner().stopScan(mScanCallback);
        callback.invoke();
    }

    @Override
    public void scan(ReadableArray serviceUUIDs, final int scanSeconds, ReadableMap options, Callback callback) {
        ScanSettings.Builder scanSettingsBuilder = new ScanSettings.Builder();
        List<ScanFilter> filters = new ArrayList<>();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && options.hasKey("legacy")) {
            scanSettingsBuilder.setLegacy(options.getBoolean("legacy"));
        }

        if (options.hasKey("scanMode")) {
            scanSettingsBuilder.setScanMode(options.getInt("scanMode"));
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (options.hasKey("numberOfMatches")) {
                scanSettingsBuilder.setNumOfMatches(options.getInt("numberOfMatches"));
            }
            if (options.hasKey("matchMode")) {
                scanSettingsBuilder.setMatchMode(options.getInt("matchMode"));
            }
            if (options.hasKey("callbackType")) {
                scanSettingsBuilder.setCallbackType(options.getInt("callbackType"));
            }
        }

        if (options.hasKey("reportDelay")) {
            scanSettingsBuilder.setReportDelay(options.getInt("reportDelay"));
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && options.hasKey("phy")) {
            int phy = options.getInt("phy");
            if (phy == BluetoothDevice.PHY_LE_CODED && getBluetoothAdapter().isLeCodedPhySupported()) {
                scanSettingsBuilder.setPhy(BluetoothDevice.PHY_LE_CODED);
            }
            if (phy == BluetoothDevice.PHY_LE_2M && getBluetoothAdapter().isLe2MPhySupported()) {
                scanSettingsBuilder.setPhy(BluetoothDevice.PHY_LE_2M);
            }
        }

        if (serviceUUIDs.size() > 0) {
            for (int i = 0; i < serviceUUIDs.size(); i++) {
                ScanFilter filter = new ScanFilter.Builder().setServiceUuid(new ParcelUuid(UUIDHelper.uuidFromString(serviceUUIDs.getString(i)))).build();
                filters.add(filter);
                Log.d(BleManager.LOG_TAG, "Filter service: " + serviceUUIDs.getString(i));
            }
        }

        // if (manufacturerInfos.size() > 0) {

        //   for(int i = 0; i < serviceUUIDs.size(); i++){
        // ReadableMap manufacturerInfo = manufacturerInfos.getMap(i);
        // ReadableMap manufacturerInfo = {
        //     id: '1',
        //     data: [],
        //     mask: [],
        // }

        // int id = manufacturerInfo.getInt("id");
        // ArrayList<Object> data = manufacturerInfo.getArray("data").toArrayList();
        // ArrayList<Object> mask = manufacturerInfo.getArray("mask").toArrayList();
        // byte[] backgroundAdvertData = new byte[data.size()];
        // byte[] backgroundAdvertMask = new byte[mask.size()];
        // for (int j = 0; j < data.size(); j++){
        //   backgroundAdvertData[j] = ((Double) data.get(j)).byteValue();
        //   backgroundAdvertMask[j] = ((Double) mask.get(j)).byteValue();
        // }
        // // Log the manufacturer data value to filter
        // StringBuilder dataString = new StringBuilder();
        // StringBuilder maskString = new StringBuilder();
        //   for (byte b : backgroundAdvertData) {
        //       dataString.append(String.format("%x", b));
        //       dataString.append(", ");
        //   }
        //   for (byte b : backgroundAdvertMask) {
        //       maskString.append(String.format("%x", b));
        //       maskString.append(", ");
        //   }
        // Log.d(bleManager.LOG_TAG,"ManufacturerDataFilter: " + dataString);
        // Log.d(bleManager.LOG_TAG,"ManufacturerMaskFilter: " + maskString);

        // filters.add(new ScanFilter.Builder().setManufacturerData(id, backgroundAdvertData, backgroundAdvertMask).build());
        //   }
        // }

        if (options.hasKey("exactAdvertisingName")) {
            String expectedName = options.getString("exactAdvertisingName");
            Log.d(BleManager.LOG_TAG, "Filter on advertising name:" + expectedName);
            ScanFilter filter = new ScanFilter.Builder().setDeviceName(expectedName).build();
            filters.add(filter);
        }

        // TODO MGA: writeCharacteristic() may throw exception if user does not grant permissions !! introduce checks in lib instead of relying on client apps guards
        getBluetoothAdapter().getBluetoothLeScanner().startScan(filters, scanSettingsBuilder.build(), mScanCallback);

        // try to stop scan after given seconds. otherwise let OS decides
        if (scanSeconds > 0) {
            Thread thread = new Thread() {
                private final int currentScanSession = scanSessionId.incrementAndGet();

                @Override
                public void run() {

                    try {
                        Thread.sleep(scanSeconds * 1000);
                    } catch (InterruptedException ignored) {
                    }

                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            BluetoothAdapter btAdapter = getBluetoothAdapter();

                            Log.d(BleManager.LOG_TAG, "ScanManager stopping scan after required time" + scanSeconds);
                            Log.d(BleManager.LOG_TAG, "ScanManager stopping scan, currentScanSession=" + currentScanSession + " , scanSessionId=" + scanSessionId.intValue() + ", bleAdapter=" + btAdapter);

                            // check current scan session was not stopped
                            if (scanSessionId.intValue() == currentScanSession) {
                                if (btAdapter.getState() == BluetoothAdapter.STATE_ON) {
                                    // TODO MGA: writeCharacteristic() may throw exception if user does not grant permissions !! introduce checks in lib instead of relying on client apps guards
                                    btAdapter.getBluetoothLeScanner().stopScan(mScanCallback);
                                }

                                Log.d(BleManager.LOG_TAG, "ScanManager stopScan called. sending stop status 10.");
                                WritableMap map = Arguments.createMap();
                                map.putInt("status", 10);
                                bleManager.sendEvent("BleManagerStopScan", map);
                            }
                        }
                    });

                }

            };
            thread.start();
        }
        callback.invoke();
    }

    private void onDiscoveredPeripheral(final ScanResult result) {
        String info;
        ScanRecord record = result.getScanRecord();

        if (record != null) {
            info = record.getDeviceName();
        } else if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            info = result.getDevice().getName();
        } else {
            info = result.toString();
        }

        Log.i(BleManager.LOG_TAG, "DiscoverPeripheral: " + info);

        LollipopPeripheral peripheral = (LollipopPeripheral) bleManager.getPeripheral(result.getDevice());
        if (peripheral == null) {
            peripheral = new LollipopPeripheral(bleManager.getReactContext(), result);
        } else {
            peripheral.updateData(result);
            peripheral.updateRssi(result.getRssi());
        }
        bleManager.savePeripheral(peripheral);

        WritableMap map = peripheral.asWritableMap();
        bleManager.sendEvent("BleManagerDiscoverPeripheral", map);
    }

    private final ScanCallback mScanCallback = new ScanCallback() {
        @Override
        public void onScanResult(final int callbackType, final ScanResult result) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Log.d(BleManager.LOG_TAG, "onScanResult: " + result);
                    onDiscoveredPeripheral(result);
                }
            });
        }

        @Override
        public void onBatchScanResults(final List<ScanResult> results) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (results.isEmpty()) {
                        Log.d(BleManager.LOG_TAG, "BatchScanResults empty");
                        return;
                    }

                    for (ScanResult result : results) {
                        Log.d(BleManager.LOG_TAG, "onBatchScanResults: " + results);
                        onDiscoveredPeripheral(result);
                    }
                }
            });
        }

        @Override
        public void onScanFailed(final int errorCode) {
            WritableMap map = Arguments.createMap();
            map.putInt("status", errorCode);
            bleManager.sendEvent("BleManagerStopScan", map);
        }
    };
}
