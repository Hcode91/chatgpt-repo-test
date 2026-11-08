# Android Vulkan SurfaceView test

V0.6 adds a genuinely visible native Vulkan rendering path to the app.
A SurfaceView supplies ANativeWindow, the JNI renderer creates a
VK_KHR_android_surface and swapchain, acquires images and records transfer
operations that clear the swapchain to changing colours, then presents
through vkQueuePresentKHR. It tears down all native handles and supports
cancellation on surface destruction.

This validates Android native-window Vulkan presentation on the device.
**It does not yet draw PS3 RSX triangles, execute RSX shaders or show
inFAMOUS graphics.** The upstream PS3 boot test remains headless and
separate, pending a full RSX-to-Vulkan renderer implementation.

In the app tap "TEST VULKAN GRAPHICS ON SCREEN".
The screen should animate colour for around five seconds before reporting
the number of presented frames. Back returns to the port laboratory.
