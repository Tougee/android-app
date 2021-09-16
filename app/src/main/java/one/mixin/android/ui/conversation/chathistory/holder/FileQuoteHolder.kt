package one.mixin.android.ui.conversation.chathistory.holder

import android.view.View
import android.widget.SeekBar
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.widget.TextViewCompat
import com.google.android.exoplayer2.util.MimeTypes
import one.mixin.android.R
import one.mixin.android.databinding.ItemChatFileQuoteBinding
import one.mixin.android.extension.fileSize
import one.mixin.android.extension.timeAgoClock
import one.mixin.android.job.MixinJobManager
import one.mixin.android.session.Session
import one.mixin.android.ui.conversation.chathistory.TranscriptAdapter
import one.mixin.android.util.GsonHelper
import one.mixin.android.util.MusicPlayer
import one.mixin.android.vo.ChatHistoryMessageItem
import one.mixin.android.vo.MediaStatus
import one.mixin.android.vo.MessageStatus
import one.mixin.android.vo.QuoteMessageItem
import one.mixin.android.vo.SnakeQuoteMessageItem
import org.jetbrains.anko.dip
import org.jetbrains.anko.textResource

class FileQuoteHolder constructor(val binding: ItemChatFileQuoteBinding) : MediaHolder(binding.root) {
    override fun chatLayout(isMe: Boolean, isLast: Boolean, isBlink: Boolean) {
        super.chatLayout(isMe, isLast, isBlink)
        if (isMe) {
            (binding.chatMsgLayout.layoutParams as ConstraintLayout.LayoutParams).horizontalBias = 1f
            if (isLast) {
                setItemBackgroundResource(
                    binding.chatLayout,
                    R.drawable.chat_bubble_reply_me_last,
                    R.drawable.chat_bubble_reply_me_last_night
                )
            } else {
                setItemBackgroundResource(
                    binding.chatLayout,
                    R.drawable.chat_bubble_reply_me,
                    R.drawable.chat_bubble_reply_me_night
                )
            }
        } else {
            (binding.chatMsgLayout.layoutParams as ConstraintLayout.LayoutParams).horizontalBias = 0f
            if (isLast) {
                setItemBackgroundResource(
                    binding.chatLayout,
                    R.drawable.chat_bubble_reply_other_last,
                    R.drawable.chat_bubble_reply_other_last_night
                )
            } else {
                setItemBackgroundResource(
                    binding.chatLayout,
                    R.drawable.chat_bubble_reply_other,
                    R.drawable.chat_bubble_reply_other_night
                )
            }
        }
    }

    fun bind(
        messageItem: ChatHistoryMessageItem,
        isLast: Boolean,
        isFirst: Boolean = false,
        onItemListener: TranscriptAdapter.OnItemListener
    ) {
        super.bind(messageItem)
        val isMe = messageItem.userId == Session.getAccountId()
        chatLayout(isMe, isLast)
        if (isFirst && !isMe) {
            binding.chatName.visibility = View.VISIBLE
            binding.chatName.text = messageItem.userFullName
            if (messageItem.appId != null) {
                binding.chatName.setCompoundDrawables(null, null, botIcon, null)
                binding.chatName.compoundDrawablePadding = itemView.dip(3)
            } else {
                binding.chatName.setCompoundDrawables(null, null, null, null)
            }
            binding.chatName.setTextColor(getColorById(messageItem.userId))
            binding.chatName.setOnClickListener { onItemListener.onUserClick(messageItem.userId) }
        } else {
            binding.chatName.visibility = View.GONE
        }
        binding.chatTime.timeAgoClock(messageItem.createdAt)
        setStatusIcon(isMe, MessageStatus.DELIVERED.name, isSecret = false, isRepresentative = false) { statusIcon, secretIcon, representativeIcon ->
            statusIcon?.setBounds(0, 0, dp12, dp12)
            secretIcon?.setBounds(0, 0, dp8, dp8)
            representativeIcon?.setBounds(0, 0, dp8, dp8)
            TextViewCompat.setCompoundDrawablesRelative(binding.chatTime, secretIcon ?: representativeIcon, null, statusIcon, null)
        }

        binding.fileNameTv.text = messageItem.mediaName
        if (messageItem.mediaStatus == MediaStatus.EXPIRED.name) {
            binding.bottomLayout.fileSizeTv.textResource = R.string.chat_expired
        } else {
            binding.bottomLayout.fileSizeTv.text = "${messageItem.mediaSize?.fileSize()}"
        }

        binding.bottomLayout.seekBar.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                }

                override fun onStartTrackingTouch(seekBar: SeekBar) {
                }

                override fun onStopTrackingTouch(seekBar: SeekBar) {
                    if (MimeTypes.isAudio(messageItem.mediaMimeType) &&
                        MusicPlayer.isPlay(messageItem.messageId)
                    ) {
                        MusicPlayer.seekTo(seekBar.progress)
                    }
                }
            }
        )
        messageItem.mediaStatus?.let {
            when (it) {
                MediaStatus.EXPIRED.name -> {
                    binding.fileExpired.visibility = View.VISIBLE
                    binding.fileProgress.visibility = View.INVISIBLE
                    binding.chatLayout.setOnClickListener {
                    }
                }
                MediaStatus.PENDING.name -> {
                    binding.fileExpired.visibility = View.GONE
                    binding.fileProgress.visibility = View.VISIBLE
                    binding.fileProgress.enableLoading(MixinJobManager.getAttachmentProcess(messageItem.messageId))
                    binding.fileProgress.setBindOnly("${messageItem.transcriptId ?: ""}${messageItem.messageId}")
                    binding.fileProgress.setOnClickListener {
                        onItemListener.onCancel(messageItem.transcriptId, messageItem.messageId)
                    }
                    binding.chatLayout.setOnClickListener {
                        handleClick(messageItem, onItemListener)
                    }
                }
                MediaStatus.DONE.name, MediaStatus.READ.name -> {
                    binding.fileExpired.visibility = View.GONE
                    binding.fileProgress.visibility = View.VISIBLE
                    if (MimeTypes.isAudio(messageItem.mediaMimeType)) {
                        binding.fileProgress.setBindOnly("${messageItem.transcriptId ?: ""}${messageItem.messageId}")
                        binding.bottomLayout.bindId = "${messageItem.transcriptId ?: ""}${messageItem.messageId}"
                        if (MusicPlayer.isPlay(messageItem.messageId)) {
                            binding.fileProgress.setPause()
                            binding.bottomLayout.showSeekBar()
                        } else {
                            binding.fileProgress.setPlay()
                            binding.bottomLayout.showText()
                        }
                        binding.fileProgress.setOnClickListener {
                            onItemListener.onAudioFileClick(messageItem)
                        }
                    } else {
                        binding.fileProgress.setDone()
                        binding.fileProgress.setBindId(null)
                        binding.bottomLayout.bindId = null
                        binding.fileProgress.setOnClickListener {
                        }
                    }
                    binding.chatLayout.setOnClickListener {
                        if (MusicPlayer.isPlay(messageItem.messageId)) {
                            onItemListener.onAudioFileClick(messageItem)
                        } else {
                            handleClick(messageItem, onItemListener)
                        }
                    }
                }
                MediaStatus.CANCELED.name -> {
                    binding.fileExpired.visibility = View.GONE
                    binding.fileProgress.visibility = View.VISIBLE
                    if (isMe && messageItem.mediaUrl != null) {
                        binding.fileProgress.enableUpload()
                    } else {
                        binding.fileProgress.enableDownload()
                    }
                    binding.fileProgress.setBindId("${messageItem.transcriptId ?: ""}${messageItem.messageId}")
                    binding.fileProgress.setProgress(-1)
                    binding.fileProgress.setOnClickListener {
                        handleClick(messageItem, onItemListener)
                    }
                    binding.chatLayout.setOnClickListener {
                        handleClick(messageItem, onItemListener)
                    }
                }
            }
        }
        try {
            binding.chatQuote.bind(GsonHelper.customGson.fromJson(messageItem.quoteContent, SnakeQuoteMessageItem::class.java))
        } catch (e: Exception) {
            binding.chatQuote.bind(GsonHelper.customGson.fromJson(messageItem.quoteContent, QuoteMessageItem::class.java))
        }

        binding.chatQuote.setOnClickListener {
            onItemListener.onQuoteMessageClick(messageItem.messageId, messageItem.quoteId)
        }
        if (messageItem.transcriptId == null) {
            binding.root.setOnClickListener {
                onItemListener.onMenu(binding.chatJump, messageItem)
            }
            chatJumpLayout(binding.chatJump, isMe, messageItem.messageId, R.id.chat_msg_layout, onItemListener)
        }
    }

    private fun handleClick(
        messageItem: ChatHistoryMessageItem,
        onItemListener: TranscriptAdapter.OnItemListener
    ) {
        if (messageItem.mediaStatus == MediaStatus.CANCELED.name) {
            if (messageItem.mediaUrl.isNullOrEmpty()) {
                onItemListener.onRetryDownload(messageItem.transcriptId, messageItem.messageId)
            } else {
                onItemListener.onRetryUpload(messageItem.transcriptId, messageItem.messageId)
            }
        } else if (messageItem.mediaStatus == MediaStatus.PENDING.name) {
            onItemListener.onCancel(messageItem.transcriptId, messageItem.messageId)
        } else if (messageItem.mediaStatus != MediaStatus.EXPIRED.name) {
            onItemListener.onFileClick(messageItem)
        }
    }
}
