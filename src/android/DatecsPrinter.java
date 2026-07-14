package com.giorgiofellipe.datecsprinter;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.CordovaInterface;
import org.apache.cordova.CordovaWebView;
import org.json.JSONObject;
import org.json.JSONArray;
import org.json.JSONException;
import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DatecsPrinter extends CordovaPlugin {
	private static final int REQUEST_BLUETOOTH_PERMISSIONS = 1001;
	private DatecsSDKWrapper printer;
	private Option pendingPermissionOption;
	private CallbackContext pendingPermissionCallbackContext;
	/**
	 * Single-thread executor that serializes all printer operations on one background thread.
	 * Serialization is required because:
	 *   1. Printer commands must be delivered in order.
	 *   2. The SDK's printer/socket state is shared across calls.
	 *   3. Using a pool would allow concurrent access that could corrupt state or
	 *      deliver results to the wrong JavaScript callback.
	 */
	private ExecutorService printerExecutor;

	private enum Option {
		listBluetoothDevices,
				connect,
				disconnect,
				feedPaper,
				printText,
				getStatus,
				getTemperature,
				setBarcode,
				printBarcode,
				printQRCode,
				printImage,
				printLogo,
				printSelfTest,
				setPageRegion,
				selectPageMode,
				selectStandardMode,
				drawPageRectangle,
				drawPageFrame,
				printPage,
				write,
				writeHex;
	}

	public void initialize(CordovaInterface cordova, CordovaWebView webView) {
		super.initialize(cordova, webView);
		printer = new DatecsSDKWrapper(cordova);
		printer.setWebView(webView);
		printerExecutor = Executors.newSingleThreadExecutor();
	}

	@Override
	public void onDestroy() {
		printerExecutor.shutdown();
		super.onDestroy();
	}

	@Override
	public boolean execute(String action, JSONArray args, CallbackContext callbackContext) throws JSONException {
		// callbackContext is passed directly to each operation below; the former
		// shared printer.setCallbackContext() has been removed to prevent races where
		// a later call could overwrite the context before an earlier call reports back.

		Option option = null;
		try {
			option = Option.valueOf(action);
		} catch (Exception e) {
			return false;
		}
		switch (option) {
			case listBluetoothDevices:
				// Permission check is fast (non-blocking) and must remain on this thread
				if (ensureBluetoothPermission(option, callbackContext)) {
					final CallbackContext ctx = callbackContext;
					printerExecutor.execute(new Runnable() {
						@Override
						public void run() {
							printer.getBluetoothPairedDevices(ctx);
						}
					});
				}
				break;
			case connect: {
				final String address = args.getString(0);
				// Store address immediately so it is available after permission grant
				printer.setAddress(address);
				if (ensureBluetoothPermission(option, callbackContext)) {
					final CallbackContext ctx = callbackContext;
					printerExecutor.execute(new Runnable() {
						@Override
						public void run() {
							printer.connect(ctx);
						}
					});
				}
				break;
			}
			case disconnect: {
				final CallbackContext ctx = callbackContext;
				printerExecutor.execute(new Runnable() {
					@Override
					public void run() {
						try {
							printer.closeActiveConnections();
							ctx.success(DatecsUtil.getStringFromStringResource(
									DatecsPrinter.this.cordova.getActivity().getApplication(),
									"printer_disconnected"));
						} catch (Exception e) {
							ctx.success(DatecsUtil.getStringFromStringResource(
									DatecsPrinter.this.cordova.getActivity().getApplication(),
									"err_disconnect_printer"));
						}
					}
				});
				break;
			}
			case feedPaper: {
				final int lines = args.getInt(0);
				final CallbackContext ctx = callbackContext;
				printerExecutor.execute(new Runnable() {
					@Override
					public void run() {
						printer.feedPaper(lines, ctx);
					}
				});
				break;
			}
			case printText: {
				final String text = args.getString(0);
				final String charset = args.getString(1);
				final CallbackContext ctx = callbackContext;
				printerExecutor.execute(new Runnable() {
					@Override
					public void run() {
						printer.printTaggedText(text, charset, ctx);
					}
				});
				break;
			}
			case getStatus: {
				final CallbackContext ctx = callbackContext;
				printerExecutor.execute(new Runnable() {
					@Override
					public void run() {
						printer.getStatus(ctx);
					}
				});
				break;
			}
			case getTemperature: {
				final CallbackContext ctx = callbackContext;
				printerExecutor.execute(new Runnable() {
					@Override
					public void run() {
						printer.getTemperature(ctx);
					}
				});
				break;
			}
			case setBarcode: {
				final int align = args.getInt(0);
				final boolean small = args.getBoolean(1);
				final int scale = args.getInt(2);
				final int hri = args.getInt(3);
				final int height = args.getInt(4);
				final CallbackContext ctx = callbackContext;
				printerExecutor.execute(new Runnable() {
					@Override
					public void run() {
						printer.setBarcode(align, small, scale, hri, height, ctx);
					}
				});
				break;
			}
			case printBarcode: {
				final int type = args.getInt(0);
				final String data = args.getString(1);
				final CallbackContext ctx = callbackContext;
				printerExecutor.execute(new Runnable() {
					@Override
					public void run() {
						printer.printBarcode(type, data, ctx);
					}
				});
				break;
			}
			case printQRCode: {
				final int size = args.getInt(0);
				final int eccLv = args.getInt(1);
				final String data = args.getString(2);
				final CallbackContext ctx = callbackContext;
				printerExecutor.execute(new Runnable() {
					@Override
					public void run() {
						printer.printQRCode(size, eccLv, data, ctx);
					}
				});
				break;
			}
			case printImage: {
				final String image = args.getString(0);
				final int imgWidth = args.getInt(1);
				final int imgHeight = args.getInt(2);
				final int imgAlign = args.getInt(3);
				final CallbackContext ctx = callbackContext;
				printerExecutor.execute(new Runnable() {
					@Override
					public void run() {
						printer.printImage(image, imgWidth, imgHeight, imgAlign, ctx);
					}
				});
				break;
			}
			case printLogo:
				break;
			case printSelfTest: {
				final CallbackContext ctx = callbackContext;
				printerExecutor.execute(new Runnable() {
					@Override
					public void run() {
						printer.printSelfTest(ctx);
					}
				});
				break;
			}
			case drawPageRectangle: {
				final int x = args.getInt(0);
				final int y = args.getInt(1);
				final int width = args.getInt(2);
				final int height = args.getInt(3);
				final int fillMode = args.getInt(4);
				final CallbackContext ctx = callbackContext;
				printerExecutor.execute(new Runnable() {
					@Override
					public void run() {
						printer.drawPageRectangle(x, y, width, height, fillMode, ctx);
					}
				});
				break;
			}
			case selectPageMode: {
				final CallbackContext ctx = callbackContext;
				printerExecutor.execute(new Runnable() {
					@Override
					public void run() {
						printer.selectPageMode(ctx);
					}
				});
				break;
			}
			case selectStandardMode: {
				final CallbackContext ctx = callbackContext;
				printerExecutor.execute(new Runnable() {
					@Override
					public void run() {
						printer.selectStandardMode(ctx);
					}
				});
				break;
			}
			case setPageRegion: {
				final int x = args.getInt(0);
				final int y = args.getInt(1);
				final int width = args.getInt(2);
				final int height = args.getInt(3);
				final int direction = args.getInt(4);
				final CallbackContext ctx = callbackContext;
				printerExecutor.execute(new Runnable() {
					@Override
					public void run() {
						printer.setPageRegion(x, y, width, height, direction, ctx);
					}
				});
				break;
			}
			case drawPageFrame: {
				final int x = args.getInt(0);
				final int y = args.getInt(1);
				final int width = args.getInt(2);
				final int height = args.getInt(3);
				final int fillMode = args.getInt(4);
				final int thickness = args.getInt(5);
				final CallbackContext ctx = callbackContext;
				printerExecutor.execute(new Runnable() {
					@Override
					public void run() {
						printer.drawPageFrame(x, y, width, height, fillMode, thickness, ctx);
					}
				});
				break;
			}
			case printPage: {
				final CallbackContext ctx = callbackContext;
				printerExecutor.execute(new Runnable() {
					@Override
					public void run() {
						printer.printPage(ctx);
					}
				});
				break;
			}
			case write: {
				final byte[] bytes = args.getString(0).getBytes();
				final CallbackContext ctx = callbackContext;
				printerExecutor.execute(new Runnable() {
					@Override
					public void run() {
						printer.write(bytes, ctx);
					}
				});
				break;
			}
			case writeHex: {
				final String hex = args.getString(0);
				final CallbackContext ctx = callbackContext;
				printerExecutor.execute(new Runnable() {
					@Override
					public void run() {
						printer.writeHex(hex, ctx);
					}
				});
				break;
			}
		}
		return true;
	}

	private String[] getBluetoothPermissions(Option option) {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
			return new String[0];
		}

		switch (option) {
			case connect:
				return new String[] {
					Manifest.permission.BLUETOOTH_CONNECT,
					Manifest.permission.BLUETOOTH_SCAN
				};
			case listBluetoothDevices:
			default:
				return new String[] {
					Manifest.permission.BLUETOOTH_CONNECT
				};
		}
	}

	private boolean hasBluetoothPermissions(String[] permissions) {
		for (String permission : permissions) {
			if (!cordova.hasPermission(permission)) {
				return false;
			}
		}

		return true;
	}

	private boolean ensureBluetoothPermission(Option option, CallbackContext callbackContext) {
		String[] permissions = getBluetoothPermissions(option);
		if (permissions.length == 0 || hasBluetoothPermissions(permissions)) {
			return true;
		}

		pendingPermissionOption = option;
		pendingPermissionCallbackContext = callbackContext;
		cordova.requestPermissions(this, REQUEST_BLUETOOTH_PERMISSIONS, permissions);
		return false;
	}

	@Override
	public void onRequestPermissionResult(int requestCode, String[] permissions, int[] grantResults) throws JSONException {
		if (requestCode != REQUEST_BLUETOOTH_PERMISSIONS) {
			super.onRequestPermissionResult(requestCode, permissions, grantResults);
			return;
		}

		final CallbackContext callbackContext = pendingPermissionCallbackContext;
		final Option option = pendingPermissionOption;
		pendingPermissionCallbackContext = null;
		pendingPermissionOption = null;

		boolean granted = grantResults.length == permissions.length;
		for (int grantResult : grantResults) {
			granted = granted && grantResult == PackageManager.PERMISSION_GRANTED;
		}
		if (!granted) {
			if (callbackContext != null) {
				callbackContext.error(printer.getBluetoothPermissionDeniedError());
			}
			return;
		}

		if (callbackContext == null || option == null) {
			return;
		}

		if (!hasBluetoothPermissions(getBluetoothPermissions(option))) {
			callbackContext.error(printer.getBluetoothPermissionDeniedError());
			return;
		}

		// Dispatch the deferred operation to the printer executor now that permission is granted
		switch (option) {
			case listBluetoothDevices:
				printerExecutor.execute(new Runnable() {
					@Override
					public void run() {
						printer.getBluetoothPairedDevices(callbackContext);
					}
				});
				break;
			case connect:
				printerExecutor.execute(new Runnable() {
					@Override
					public void run() {
						printer.connect(callbackContext);
					}
				});
				break;
			default:
				break;
		}
	}
}
