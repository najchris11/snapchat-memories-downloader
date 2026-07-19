package com.najdev.snapvault

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerMode
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIViewController
import platform.darwin.NSObject

class IosPickers : PlatformPickers {
    override fun pickHtmlFile(onResult: (String?) -> Unit) {
        presentPicker(types = listOf("public.html", "public.plain-text"), multiple = false) { urls ->
            onResult(urls.firstOrNull()?.path)
        }
    }

    override fun pickFolder(onResult: (String?) -> Unit) {
        presentPicker(types = listOf("public.folder"), multiple = false) { urls ->
            onResult(urls.firstOrNull()?.path)
        }
    }

    override fun pickMultipleZips(onResult: (List<String>) -> Unit) {
        presentPicker(types = listOf("public.zip-archive", "com.pkware.zip-archive"), multiple = true) { urls ->
            onResult(urls.mapNotNull { it.path })
        }
    }

    private fun presentPicker(
        types: List<String>,
        multiple: Boolean,
        onPicked: (List<NSURL>) -> Unit
    ) {
        val rootVc = topViewController() ?: run {
            onPicked(emptyList())
            return
        }
        val picker = UIDocumentPickerViewController(
            documentTypes = types,
            inMode = UIDocumentPickerMode.UIDocumentPickerModeOpen
        ).apply {
            allowsMultipleSelection = multiple
            delegate = PickerDelegate(onPicked)
        }
        rootVc.presentViewController(picker, animated = true, completion = null)
    }

    private fun topViewController(): UIViewController? {
        val keyWindow = UIApplication.sharedApplication.keyWindow
        var top = keyWindow?.rootViewController
        while (top?.presentedViewController != null) {
            top = top.presentedViewController
        }
        return top
    }
}

private class PickerDelegate(
    private val onPicked: (List<NSURL>) -> Unit
) : NSObject(), UIDocumentPickerDelegateProtocol {

    override fun documentPicker(
        controller: UIDocumentPickerViewController,
        didPickDocumentsAtURLs: List<*>
    ) {
        val urls = didPickDocumentsAtURLs.filterIsInstance<NSURL>()
        urls.forEach { url ->
            url.startAccessingSecurityScopedResource()
        }
        onPicked(urls)
    }

    override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
        onPicked(emptyList())
    }
}

@Composable
actual fun rememberPlatformPickers(): PlatformPickers = remember { IosPickers() }
