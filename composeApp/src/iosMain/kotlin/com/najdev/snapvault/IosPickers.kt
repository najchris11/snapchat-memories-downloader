package com.najdev.snapvault

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerMode
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UISceneActivationStateForegroundActive
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene
import platform.darwin.NSObject

private enum class PickerSlot { HTML, OUTPUT_FOLDER, ZIP_FOLDER, ZIP_FILES }

class IosPickers : PlatformPickers {
    // Retain a strong reference to the active delegate while the picker is presented.
    // UIDocumentPickerViewController.delegate is a weak reference in UIKit.
    private var activeDelegate: PickerDelegate? = null

    // Track active security-scoped URLs per slot so HTML index, ZIP source, and Output folder
    // stay concurrently accessible during sync execution without revoking each other.
    private val accessedUrlsBySlot = mutableMapOf<PickerSlot, List<NSURL>>()

    override fun pickHtmlFile(onResult: (String?) -> Unit) {
        presentPicker(slot = PickerSlot.HTML, types = listOf("public.html", "public.plain-text"), multiple = false) { urls ->
            onResult(urls.firstOrNull()?.path)
        }
    }

    override fun pickFolder(onResult: (String?) -> Unit) {
        pickOutputFolder(onResult)
    }

    override fun pickOutputFolder(onResult: (String?) -> Unit) {
        presentPicker(slot = PickerSlot.OUTPUT_FOLDER, types = listOf("public.folder"), multiple = false) { urls ->
            onResult(urls.firstOrNull()?.path)
        }
    }

    override fun pickZipFolder(onResult: (String?) -> Unit) {
        presentPicker(slot = PickerSlot.ZIP_FOLDER, types = listOf("public.folder"), multiple = false) { urls ->
            onResult(urls.firstOrNull()?.path)
        }
    }

    override fun pickMultipleZips(onResult: (List<String>) -> Unit) {
        presentPicker(slot = PickerSlot.ZIP_FILES, types = listOf("public.zip-archive", "com.pkware.zip-archive"), multiple = true) { urls ->
            onResult(urls.mapNotNull { it.path })
        }
    }

    override fun releaseAllSecurityAccess() {
        accessedUrlsBySlot.values.flatten().forEach { url ->
            try {
                url.stopAccessingSecurityScopedResource()
            } catch (_: Exception) {}
        }
        accessedUrlsBySlot.clear()
    }

    private fun stopSecurityAccessForSlot(slot: PickerSlot) {
        accessedUrlsBySlot.remove(slot)?.forEach { url ->
            try {
                url.stopAccessingSecurityScopedResource()
            } catch (_: Exception) {}
        }
    }

    private fun presentPicker(
        slot: PickerSlot,
        types: List<String>,
        multiple: Boolean,
        onPicked: (List<NSURL>) -> Unit
    ) {
        // Stop security access only for prior selections of this specific slot
        stopSecurityAccessForSlot(slot)

        val rootVc = topViewController() ?: run {
            onPicked(emptyList())
            return
        }

        val delegate = PickerDelegate { urls, startedUrls ->
            activeDelegate = null
            if (startedUrls.isNotEmpty()) {
                accessedUrlsBySlot[slot] = startedUrls
            }
            onPicked(urls)
        }
        activeDelegate = delegate

        val picker = UIDocumentPickerViewController(
            documentTypes = types,
            inMode = UIDocumentPickerMode.UIDocumentPickerModeOpen
        ).apply {
            allowsMultipleSelection = multiple
            this.delegate = delegate
        }
        rootVc.presentViewController(picker, animated = true, completion = null)
    }

    private fun topViewController(): UIViewController? {
        val scenes = UIApplication.sharedApplication.connectedScenes
        val windowScene = scenes.filterIsInstance<UIWindowScene>().firstOrNull { it.activationState == UISceneActivationStateForegroundActive }
            ?: scenes.filterIsInstance<UIWindowScene>().firstOrNull()
        val keyWindow = windowScene?.windows?.filterIsInstance<UIWindow>()?.firstOrNull { it.isKeyWindow() }
            ?: UIApplication.sharedApplication.keyWindow
        var top = keyWindow?.rootViewController
        while (top?.presentedViewController != null) {
            top = top.presentedViewController
        }
        return top
    }
}

private class PickerDelegate(
    private val onPicked: (urls: List<NSURL>, startedUrls: List<NSURL>) -> Unit
) : NSObject(), UIDocumentPickerDelegateProtocol {

    override fun documentPicker(
        controller: UIDocumentPickerViewController,
        didPickDocumentsAtURLs: List<*>
    ) {
        val urls = didPickDocumentsAtURLs.filterIsInstance<NSURL>()
        val startedUrls = mutableListOf<NSURL>()
        urls.forEach { url ->
            if (url.startAccessingSecurityScopedResource()) {
                startedUrls.add(url)
            }
        }
        onPicked(urls, startedUrls)
    }

    override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
        onPicked(emptyList(), emptyList())
    }
}

@Composable
actual fun rememberPlatformPickers(): PlatformPickers = remember { IosPickers() }
