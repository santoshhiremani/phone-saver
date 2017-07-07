package link.standen.michael.phonesaver.activity

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.AsyncTask
import android.os.Build
import android.os.Bundle
import android.util.Log
import link.standen.michael.phonesaver.R
import link.standen.michael.phonesaver.util.LocationHelper
import android.provider.OpenableColumns
import android.text.Html
import android.text.method.LinkMovementMethod
import android.view.View
import android.webkit.MimeTypeMap
import android.widget.*
import java.io.*
import java.net.MalformedURLException
import java.net.URL

/**
 * An activity to handle saving files.
 * https://developer.android.com/training/sharing/receive.html
 */
class SaverActivity : ListActivity() {

	private val TAG = "SaverActivity"

	private val FILENAME_REGEX = "[^-_.A-Za-z0-9]"
	private val FILENAME_LENGTH_LIMIT = 100

	private var location: String? = null

	data class Pair(val key: String, val value: String)
	private var debugInfo: MutableList<Pair> = mutableListOf()

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.saver_activity)

		useIntent({ success ->
			Log.i(TAG, "Supported: $success")
			// Success should never be null on a dryRun
			if (success!!){
				loadList()
			} else {
				showNotSupported()
			}
		}, dryRun=true)
	}

	fun loadList() {
		LocationHelper.loadFolderList(this)?.let {
			if (it.size > 1) {
				runOnUiThread {
					findViewById(R.id.loading).visibility = View.GONE
					// Init list view
					val listView = findViewById(android.R.id.list) as ListView
					listView.onItemClickListener = AdapterView.OnItemClickListener { _, view, _, _ ->
						location = LocationHelper.addRoot((view as TextView).text.toString())
						useIntent({ finishIntent(it) })
					}
					listView.adapter = ArrayAdapter<String>(this, R.layout.saver_list_item, it.map { if (it.isBlank()) File.separator else it })
				}
				return // await selection
			} else if (it.size == 1) {
				// Only one location, just use it
				location = LocationHelper.addRoot(it[0])
				useIntent({ finishIntent(it) })
				return // activity dead
			} else {
				Toast.makeText(this, R.string.toast_save_init_no_locations, Toast.LENGTH_LONG).show()
				exitApplication()
				return // activity dead
			}
		}

		Toast.makeText(this, R.string.toast_save_init_error, Toast.LENGTH_LONG).show()
		exitApplication()
		return // activity dead
	}

	fun useIntent(callback: (success: Boolean?) -> Unit, dryRun: Boolean = false) {
		// Get intent action and MIME type
		val action: String? = intent.action
		val type: String? = intent.type

		Log.i(TAG, "Action: $action")
		Log.i(TAG, "Type: $type")

		type?.let {
			if (Intent.ACTION_SEND == action) {
				if (type.startsWith("image/") || type.startsWith("video/") ||
						type == "application/octet-stream") {
					// Handle single stream being sent
					return handleStream(callback, dryRun)
				} else if (type == "text/plain") {
					return handleText(callback, dryRun)
				}
			} else if (Intent.ACTION_SEND_MULTIPLE == action) {
				if (type.startsWith("image/")) {
					// Handle multiple images being sent
					return handleMultipleImages(callback, dryRun)
				}
			}
		}

		// Failed to reach callback
		finishIntent(false)
	}

	/**
	 * Show the not supported information.
	 */
	fun showNotSupported() {
		// Hide list
		runOnUiThread {
			findViewById(R.id.loading).visibility = View.GONE
			findViewById(android.R.id.list).visibility = View.GONE
			// Generate issue text here as should always be English and does not need to be in strings.xml
			val bob = StringBuilder()
			bob.append("https://github.com/ScreamingHawk/phone-saver/issues/new?title=")
			bob.append("Support Request - ")
			bob.append(intent.type)
			bob.append("&body=")
			bob.append("Support request. Generated by Phone Saver.%0D%0A")
			bob.append("%0D%0AIntent type: ")
			bob.append(intent.type)
			bob.append("%0D%0AIntent action: ")
			bob.append(intent.action)
			intent.getStringExtra(Intent.EXTRA_TEXT)?.let {
				bob.append("%0D%0AText: ")
				bob.append(it)
			}
			intent.getStringExtra(Intent.EXTRA_SUBJECT)?.let {
				bob.append("%0D%0ASubject: ")
				bob.append(it)
			}
			debugInfo.forEach {
				bob.append("%0D%0A${it.key}: ")
				bob.append(it.value)
			}
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
				intent.getStringExtra(Intent.EXTRA_HTML_TEXT)?.let {
					bob.append("%0D%0AHTML Text: ")
					bob.append(it)
				}
			}
			// Version
			try {
				val versionName = packageManager.getPackageInfo(packageName, 0).versionName
				bob.append("%0D%0AApplication Version: ")
				bob.append(versionName)
			} catch (e: PackageManager.NameNotFoundException) {
				Log.e(TAG, "Unable to get package version", e)
			}
			bob.append("%0D%0A%0D%0AMore information: TYPE_ADDITIONAL_INFORMATION_HERE")
			bob.append("%0D%0A%0D%0AThank you")
			val issueLink = bob.toString().replace(" ", "%20")

			// Build and show unsupported message
			val supportView = findViewById(R.id.not_supported) as TextView
			supportView.text = Html.fromHtml(resources.getString(R.string.not_supported, issueLink))
			supportView.movementMethod = LinkMovementMethod.getInstance()
			findViewById(R.id.not_supported_wrapper).visibility = View.VISIBLE
		}
	}

	/**
	 * Call when the intent is finished
	 */
	fun finishIntent(success: Boolean?) {
		// Notify user
		runOnUiThread {
			if (success == null){
				Toast.makeText(this, R.string.toast_save_in_progress, Toast.LENGTH_SHORT).show()
			} else if (success){
				Toast.makeText(this, R.string.toast_save_successful, Toast.LENGTH_SHORT).show()
			} else {
				Toast.makeText(this, R.string.toast_save_failed, Toast.LENGTH_SHORT).show()
			}
		}

		exitApplication()
	}

	/**
	 * Exists the application is the best way available for the Android version
	 */
	fun exitApplication() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
			finishAndRemoveTask()
		} else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
			finishAffinity()
		} else {
			finish()
		}
	}

	/**
	 * Handle the saving of intents with streams such as images and videos.
	 */
	fun handleStream(callback: (success: Boolean?) -> Unit, dryRun: Boolean) {
		intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let {
			saveUri(it, getFilename(it, intent.type), callback, dryRun)
		} ?: callback(false)
	}

	/**
	 * Handle the saving of text intents.
	 */
	fun handleText(callback: (success: Boolean?) -> Unit, dryRun: Boolean) {
		// Try save stream first
		intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let {
			Log.d(TAG, "Text has stream")
			return saveUri(it, getFilename(it, intent.type), callback, dryRun)
		}

		// Save the text
		intent.getStringExtra(Intent.EXTRA_TEXT)?.let {
			Log.d(TAG, "Text Extra: $it")
			object: AsyncTask<Unit, Unit, Unit>(){
				override fun doInBackground(vararg params: Unit?) {
					try {
						val url = URL(it)
						// It's a URL
						Log.d(TAG, "Text with URL")
						val mime = MimeTypeMap.getSingleton()
						val contentType = mime.getMimeTypeFromExtension(MimeTypeMap.getFileExtensionFromUrl(it))
								// Fall back to checking URL content type
								?: url.openConnection().getHeaderField("Content-Type")
						contentType?.let { contentType ->
							Log.d(TAG, "ContentType: $contentType")
							debugInfo.add(Pair("URL Content-Type", contentType))
							val filename = getFilename(
									intent.getStringExtra(Intent.EXTRA_SUBJECT) ?: Uri.parse(it).lastPathSegment,
									contentType)
							if (contentType.startsWith("image/") || contentType.startsWith("video/")) {
								saveUrl(Uri.parse(it), filename, callback, dryRun)
							} else {
								callback(false)
							}
						}?: callback(false)
					} catch (e: MalformedURLException){
						Log.d(TAG, "Text without URL")
						// It's just some text
						val filename = getFilename(intent.getStringExtra(Intent.EXTRA_SUBJECT) ?: it, "text/plain")
						saveString(it, filename, callback, dryRun)
					}
				}
			}.execute()
		} ?: callback(false)
	}

	/**
	 * Handle the saving of multiple image files.
	 */
	fun handleMultipleImages(callback: (success: Boolean?) -> Unit, dryRun: Boolean) {
		val imageUris: ArrayList<Uri>? = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
		imageUris?.let {
			var counter = 0
			var completeSuccess = true
			imageUris.forEach {
				saveUri(it, getFilename(it, intent.type), { success ->
					counter++
					success?.let {
						completeSuccess = completeSuccess && it
					}
					if (counter == imageUris.size){
						callback(completeSuccess)
					}
				}, dryRun)
			}
		} ?: callback(false)
	}

	/**
	 * Save the given uri to filesystem.
	 */
	fun saveUri(uri: Uri, filename: String, callback: (success: Boolean?) -> Unit, dryRun: Boolean) {
		val destinationFilename = safeAddPath(filename)

		if (!dryRun) {
			val sourceFilename = uri.path
			Log.d(TAG, "Saving $sourceFilename to $destinationFilename")
		}

		contentResolver.openInputStream(uri)?.use { bis ->
			saveStream(bis, destinationFilename, callback, dryRun)
		} ?: callback(false)
	}

	/**
	 * Save the given url to the filesystem.
	 */
	fun saveUrl(uri: Uri, filename: String, callback: (success: Boolean?) -> Unit, dryRun: Boolean) {
		if (dryRun){
			// This entire method can be skipped when doing a dry run
			return callback(true)
		}

		var success: Boolean? = false

		location?.let {
			val sourceFilename = uri.toString()
			val destinationFilename = safeAddPath(filename)

			Log.d(TAG, "Saving $sourceFilename to $destinationFilename")

			val downloader = DownloadManager.Request(uri)
			downloader.setAllowedNetworkTypes(DownloadManager.Request.NETWORK_MOBILE or DownloadManager.Request.NETWORK_WIFI)
					.setAllowedOverRoaming(true)
					.setTitle(filename)
					.setDescription(resources.getString(R.string.downloader_description, sourceFilename))
					.setDestinationInExternalPublicDir(LocationHelper.removeRoot(it), filename)

			(getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(downloader)

			success = null
		}

		callback(success)
	}

	/**
	 * Save a stream to the filesystem.
	 */
	private fun saveStream(bis: InputStream, destinationFilename: String,
						   callback: (success: Boolean?) -> Unit, dryRun: Boolean) {
		if (dryRun){
			// This entire method can be skipped when doing a dry run
			return callback(true)
		}

		var success = false
		var bos: OutputStream? = null

		try {
			val fout = File(destinationFilename)
			if (!fout.exists()){
				fout.createNewFile()
			}
			bos = BufferedOutputStream(FileOutputStream(fout, false))
			val buf = ByteArray(1024)
			bis.read(buf)
			do {
				bos.write(buf)
			} while (bis.read(buf) != -1)

			// Done
			success = true
		} catch (e: IOException) {
			Log.e(TAG, "Unable to save file", e)
		} finally {
			try {
				bos?.close()
			} catch (e: IOException) {
				Log.e(TAG, "Unable to close stream", e)
			}
		}
		callback(success)
	}

	/**
	 * Save a string to the filesystem.
	 */
	private fun saveString(s: String, filename: String, callback: (success: Boolean?) -> Unit,
						   dryRun: Boolean) {
		if (dryRun){
			// This entire method can be skipped when doing a dry run
			return callback(true)
		}

		val destinationFilename = safeAddPath(filename)
		var success = false
		var bw: BufferedWriter? = null

		try {
			val fout = File(destinationFilename)
			if (!fout.exists()){
				fout.createNewFile()
			}
			bw = BufferedWriter(FileWriter(destinationFilename))
			bw.write(s)

			// Done
			success = true
		} catch (e: IOException) {
			Log.e(TAG, "Unable to save file", e)
		} finally {
			try {
				bw?.close()
			} catch (e: IOException) {
				Log.e(TAG, "Unable to close stream", e)
			}
		}
		callback(success)
	}

	/**
	 * Get the filename from a Uri.
	 */
	private fun getFilename(uri: Uri, mime: String): String {
		// Find the actual filename
		if (uri.scheme == "content") {
			contentResolver.query(uri, null, null, null, null)?.use {
				if (it.moveToFirst()) {
					return getFilename(it.getString(it.getColumnIndex(OpenableColumns.DISPLAY_NAME)), mime)
				}
			}
		}
		return getFilename(uri.lastPathSegment, mime)
	}

	/**
	 * Get the filename from a string.
	 */
	private fun getFilename(s: String, mime: String): String {
		Log.d(TAG, "Converting filename: $s")

		var result = s
				// Take last section after a slash
				.replaceBeforeLast("/", "")
				// Take first section before a space
				.replaceAfter(" ", "")
				// Remove non-filename characters
				.replace(Regex(FILENAME_REGEX), "")

		if (result.length > FILENAME_LENGTH_LIMIT) {
			// Do not go over the filename length limit
			result = result.substring(0, FILENAME_LENGTH_LIMIT)
		}

		if (!MimeTypeMap.getSingleton().hasExtension(result)){
			// Add file extension
			MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)?.let {
				result += "." + it
			}

		}

		Log.d(TAG, "Converted filename: $result")

		return result
	}

	/**
	 * Add the location path if not null and not already added.
	 */
	private fun safeAddPath(filename: String): String {
		location?.let {
			if (!filename.startsWith(it)){
				return it + File.separatorChar + filename
			}
		}
		return filename
	}
}
