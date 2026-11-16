# CleanView Camera 2.0
Local-network Android camera streamer. Start streaming on the phone, then open the displayed URL on a TV, laptop, tablet or another phone browser.

## How to use
1. Install the APK and grant camera permission.
2. Connect the phone and receiving device to the same Wi-Fi, or connect the receiving device to the phone hotspot.
3. Tap START STREAMING.
4. Open the displayed `http://IP:8080` address on the receiving device. Scan the QR code if convenient.
5. Use the receiver's Full screen button.

## Limitations
This first build uses MJPEG, not WebRTC. It is browser-friendly but uses more bandwidth and may have higher latency. Devices must be on the same reachable local network; router client isolation can block connections. Some smart-TV browsers may not support MJPEG. Test with a laptop browser first. No audio or recording-to-file is included. Keep the app open during streaming.
