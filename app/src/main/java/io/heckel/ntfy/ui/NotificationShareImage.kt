package io.heckel.ntfy.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import io.heckel.ntfy.BuildConfig
import io.heckel.ntfy.R
import io.heckel.ntfy.db.Notification
import io.heckel.ntfy.util.formatDateShort
import io.heckel.ntfy.util.formatMessage
import io.heckel.ntfy.util.formatTitle
import io.heckel.ntfy.util.splitTags
import io.heckel.ntfy.util.unmatchedTags
import java.io.File
import java.io.FileOutputStream

/** Creates a self-contained PNG card for sharing a received notification. */
internal object NotificationShareImage {
    private const val IMAGE_WIDTH = 1080
    private const val OUTER_MARGIN = 40f
    private const val CARD_PADDING = 64f
    private const val HEADER_ICON_SIZE = 96
    private const val MAX_MESSAGE_LINES = 32

    fun create(context: Context, notification: Notification, topicName: String): Uri {
        val bitmap = render(context, notification, topicName)
        val shareDirectory = File(context.cacheDir, "shared-notifications")
        check(shareDirectory.exists() || shareDirectory.mkdirs()) {
            "Cannot create the notification share directory"
        }
        val stableId = notification.id.hashCode().toLong() and 0xffffffffL
        val output = File(shareDirectory, "notification-$stableId.png")
        try {
            FileOutputStream(output).use { stream ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                    "Cannot encode the notification image"
                }
            }
        } finally {
            bitmap.recycle()
        }
        return FileProvider.getUriForFile(context, BuildConfig.APPLICATION_ID + ".provider", output)
    }

    private fun render(context: Context, notification: Notification, topicName: String): Bitmap {
        val cardLeft = OUTER_MARGIN
        val cardTop = OUTER_MARGIN
        val contentLeft = cardLeft + CARD_PADDING
        val contentWidth = (IMAGE_WIDTH - 2 * (OUTER_MARGIN + CARD_PADDING)).toInt()

        val appNamePaint = textPaint(42f, Color.rgb(24, 34, 30), Typeface.BOLD)
        val topicPaint = textPaint(31f, Color.rgb(92, 108, 102), Typeface.NORMAL)
        val titlePaint = textPaint(54f, Color.rgb(20, 29, 26), Typeface.BOLD)
        val messagePaint = textPaint(42f, Color.rgb(39, 49, 45), Typeface.NORMAL)
        val tagPaint = textPaint(30f, Color.rgb(51, 133, 116), Typeface.BOLD)
        val datePaint = textPaint(29f, Color.rgb(105, 118, 113), Typeface.NORMAL)

        val headerTextWidth = contentWidth - HEADER_ICON_SIZE - 28
        val appNameLayout = layout(
            context.getString(R.string.app_name),
            appNamePaint,
            headerTextWidth,
            maxLines = 1
        )
        val topicLayout = layout(topicName, topicPaint, headerTextWidth, maxLines = 1)
        val title = if (notification.title.isBlank()) "" else formatTitle(notification)
        val titleLayout = if (title.isBlank()) null else layout(title, titlePaint, contentWidth, maxLines = 3)
        val messageLayout = layout(
            formatMessage(notification).ifBlank { " " },
            messagePaint,
            contentWidth,
            maxLines = MAX_MESSAGE_LINES,
            lineSpacing = 12f
        )
        val tags = unmatchedTags(splitTags(notification.tags))
            .joinToString(separator = "   ") { "#$it" }
        val tagsLayout = if (tags.isBlank()) null else layout(tags, tagPaint, contentWidth, maxLines = 2)
        val attachment = notification.attachment?.name?.takeIf { it.isNotBlank() }
        val attachmentLayout = attachment?.let {
            layout("📎  $it", topicPaint, contentWidth, maxLines = 2)
        }
        val dateLayout = layout(formatDateShort(notification.timestamp), datePaint, contentWidth, maxLines = 1)

        var contentHeight = CARD_PADDING.toInt() + HEADER_ICON_SIZE + 52
        titleLayout?.let { contentHeight += it.height + 30 }
        contentHeight += messageLayout.height
        tagsLayout?.let { contentHeight += 34 + it.height }
        attachmentLayout?.let { contentHeight += 34 + it.height }
        contentHeight += 54 + 1 + 34 + dateLayout.height + CARD_PADDING.toInt()

        val imageHeight = maxOf(760, contentHeight + (OUTER_MARGIN * 2).toInt())
        val bitmap = Bitmap.createBitmap(IMAGE_WIDTH, imageHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(238, 247, 242))

        val cardBottom = imageHeight - OUTER_MARGIN
        val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            setShadowLayer(18f, 0f, 8f, Color.argb(35, 0, 45, 35))
        }
        canvas.drawRoundRect(
            RectF(cardLeft, cardTop, IMAGE_WIDTH - OUTER_MARGIN, cardBottom),
            42f,
            42f,
            cardPaint
        )

        val iconTop = cardTop + CARD_PADDING
        drawAppIcon(context, canvas, contentLeft, iconTop, HEADER_ICON_SIZE)
        val headerTextLeft = contentLeft + HEADER_ICON_SIZE + 28
        drawLayout(canvas, appNameLayout, headerTextLeft, iconTop + 4)
        drawLayout(canvas, topicLayout, headerTextLeft, iconTop + 55)

        var y = iconTop + HEADER_ICON_SIZE + 52
        titleLayout?.let {
            drawLayout(canvas, it, contentLeft, y)
            y += it.height + 30
        }
        drawLayout(canvas, messageLayout, contentLeft, y)
        y += messageLayout.height
        tagsLayout?.let {
            y += 34
            drawLayout(canvas, it, contentLeft, y)
            y += it.height
        }
        attachmentLayout?.let {
            y += 34
            drawLayout(canvas, it, contentLeft, y)
            y += it.height
        }

        y += 54
        val divider = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(220, 232, 226) }
        canvas.drawRect(contentLeft, y, IMAGE_WIDTH - contentLeft, y + 1, divider)
        y += 35
        drawLayout(canvas, dateLayout, contentLeft, y)

        return bitmap
    }

    private fun textPaint(size: Float, color: Int, style: Int): TextPaint {
        return TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            this.color = color
            typeface = Typeface.create("sans-serif", style)
        }
    }

    private fun layout(
        text: CharSequence,
        paint: TextPaint,
        width: Int,
        maxLines: Int,
        lineSpacing: Float = 6f
    ): StaticLayout {
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .setLineSpacing(lineSpacing, 1f)
            .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NORMAL)
            .setEllipsize(TextUtils.TruncateAt.END)
            .setEllipsizedWidth(width)
            .setMaxLines(maxLines)
            .build()
    }

    private fun drawLayout(canvas: Canvas, layout: StaticLayout, x: Float, y: Float) {
        canvas.save()
        canvas.translate(x, y)
        layout.draw(canvas)
        canvas.restore()
    }

    private fun drawAppIcon(context: Context, canvas: Canvas, x: Float, y: Float, size: Int) {
        val icon = ContextCompat.getDrawable(context, R.mipmap.ic_launcher)
        if (icon != null) {
            icon.bounds = Rect(x.toInt(), y.toInt(), x.toInt() + size, y.toInt() + size)
            icon.draw(canvas)
            return
        }

        val fallback = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(51, 133, 116) }
        canvas.drawCircle(x + size / 2f, y + size / 2f, size / 2f, fallback)
    }
}
