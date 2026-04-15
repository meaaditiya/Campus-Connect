package com.campusconnect.service;

import com.campusconnect.dto.*;
import com.campusconnect.entity.*;
import com.campusconnect.exception.ResourceNotFoundException;
import com.campusconnect.repository.*;
import com.campusconnect.util.EncryptionUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ChatService {

    private final MessageRepository messageRepository;
    private final MessageReactionRepository reactionRepository;
    private final UserRepository userRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final EncryptionUtil encryption;

    private static final String DELETED_MSG = "This message was deleted";



    @Transactional
    public ReactionDTO toggleReaction(Long userId, ReactionRequest req) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        Message message = messageRepository.findById(req.getMessageId())
                .orElseThrow(() -> new ResourceNotFoundException("Message not found"));

        if (Boolean.TRUE.equals(message.getDeleted())) {
            throw new RuntimeException("Cannot react to a deleted message");
        }


        Long senderId = message.getSender().getId();
        Long receiverId = message.getReceiver().getId();
        if (!userId.equals(senderId) && !userId.equals(receiverId)) {
            throw new RuntimeException("You can only react to messages in your conversations");
        }

        Optional<MessageReaction> existing = reactionRepository
                .findByMessageIdAndUserIdAndEmoji(req.getMessageId(), userId, req.getEmoji());

        if (existing.isPresent()) {
            reactionRepository.delete(existing.get());

            ReactionNotificationDTO notification = ReactionNotificationDTO.builder()
                    .messageId(req.getMessageId())
                    .userId(userId)
                    .userName(user.getName())
                    .emoji(req.getEmoji())
                    .action("REMOVED")
                    .build();

            broadcastReaction(notification, senderId, receiverId);

            return null;
        } else {

            MessageReaction reaction = MessageReaction.builder()
                    .message(message)
                    .user(user)
                    .emoji(req.getEmoji())
                    .build();

            MessageReaction saved = reactionRepository.save(reaction);

            ReactionDTO dto = mapReactionToDTO(saved);

            ReactionNotificationDTO notification = ReactionNotificationDTO.builder()
                    .messageId(req.getMessageId())
                    .userId(userId)
                    .userName(user.getName())
                    .emoji(req.getEmoji())
                    .action("ADDED")
                    .build();

            broadcastReaction(notification, senderId, receiverId);

            return dto;
        }
    }

    public List<ReactionDTO> getReactions(Long userId, Long messageId) {
        Message message = messageRepository.findById(messageId)
                .orElseThrow(() -> new ResourceNotFoundException("Message not found"));


        Long senderId = message.getSender().getId();
        Long receiverId = message.getReceiver().getId();
        if (!userId.equals(senderId) && !userId.equals(receiverId)) {
            throw new RuntimeException("You can only view reactions for messages in your conversations");
        }

        return reactionRepository.findByMessageId(messageId).stream()
                .map(this::mapReactionToDTO)
                .collect(Collectors.toList());
    }

    private void broadcastReaction(ReactionNotificationDTO notification, Long senderId, Long receiverId) {
        messagingTemplate.convertAndSend("/queue/reactions/" + senderId, notification);
        if (!senderId.equals(receiverId)) {
            messagingTemplate.convertAndSend("/queue/reactions/" + receiverId, notification);
        }
    }

    private ReactionDTO mapReactionToDTO(MessageReaction r) {
        return ReactionDTO.builder()
                .id(r.getId())
                .messageId(r.getMessage().getId())
                .userId(r.getUser().getId())
                .userName(r.getUser().getName())
                .emoji(r.getEmoji())
                .createdAt(r.getCreatedAt())
                .build();
    }



    @Transactional
    public MessageDTO sendMessage(Long senderId, SendMessageRequest req) {
        User sender = userRepository.findById(senderId)
                .orElseThrow(() -> new ResourceNotFoundException("Sender not found"));
        User receiver = userRepository.findById(req.getReceiverId())
                .orElseThrow(() -> new ResourceNotFoundException("Receiver not found"));

        String encryptedContent = encryption.encrypt(req.getContent());

        Message.MessageBuilder builder = Message.builder()
                .sender(sender)
                .receiver(receiver)
                .content(encryptedContent)
                .readStatus(false)
                .deleted(false);

        if (req.getReplyToId() != null) {
            Message replyTo = messageRepository.findById(req.getReplyToId()).orElse(null);
            builder.replyTo(replyTo);
        }

        Message saved = messageRepository.save(builder.build());
        MessageDTO dto = mapToDTO(saved);

        messagingTemplate.convertAndSend("/queue/messages/" + receiver.getId(), dto);
        messagingTemplate.convertAndSend("/queue/messages/" + sender.getId(), dto);

        return dto;
    }

    public Page<MessageDTO> getConversation(Long userId, Long otherUserId, int page, int size) {
        return messageRepository.findConversation(userId, otherUserId, PageRequest.of(page, size))
                .map(this::mapToDTO);
    }

    public List<ConversationDTO> getConversations(Long userId) {
        List<Long> partnerIds = messageRepository.findConversationPartnerIds(userId);
        List<ConversationDTO> conversations = new ArrayList<>();

        for (Long partnerId : partnerIds) {
            User partner = userRepository.findById(partnerId).orElse(null);
            if (partner == null) continue;

            Message lastMsg = messageRepository.findLastMessage(userId, partnerId);
            int unread = messageRepository.countByReceiverIdAndSenderIdAndReadStatusFalse(userId, partnerId);

            String preview = "";
            if (lastMsg != null) {
                if (Boolean.TRUE.equals(lastMsg.getDeleted())) {
                    preview = DELETED_MSG;
                } else {
                    preview = decryptSafe(lastMsg.getContent());
                }
            }

            conversations.add(ConversationDTO.builder()
                    .userId(partner.getId())
                    .userName(partner.getName())
                    .userProfilePic(partner.getProfilePicUrl())
                    .lastMessage(preview)
                    .lastMessageTime(lastMsg != null ? lastMsg.getCreatedAt() : null)
                    .unreadCount(unread)
                    .build());
        }

        conversations.sort((a, b) -> {
            if (a.getLastMessageTime() == null) return 1;
            if (b.getLastMessageTime() == null) return -1;
            return b.getLastMessageTime().compareTo(a.getLastMessageTime());
        });

        return conversations;
    }

    @Transactional
    public ReadReceiptDTO markAsRead(Long userId, Long senderId) {
        LocalDateTime now = LocalDateTime.now();
        List<Long> updatedIds = messageRepository.findUnreadMessageIds(userId, senderId);

        if (!updatedIds.isEmpty()) {
            messageRepository.markAsReadWithTimestamp(userId, senderId, now);
        }

        ReadReceiptDTO receipt = ReadReceiptDTO.builder()
                .readByUserId(userId)
                .senderUserId(senderId)
                .messageIds(updatedIds)
                .readAt(now)
                .build();

        messagingTemplate.convertAndSend("/queue/read-receipt/" + senderId, receipt);

        return receipt;
    }

    @Transactional
    public MessageDeleteDTO deleteMessage(Long userId, DeleteMessageRequest req) {
        Message message = messageRepository.findById(req.getMessageId())
                .orElseThrow(() -> new ResourceNotFoundException("Message not found"));

        Long otherUserId = message.getSender().getId().equals(userId)
                ? message.getReceiver().getId()
                : message.getSender().getId();

        String type = req.getDeleteType();

        if ("FOR_EVERYONE".equals(type)) {
            if (!message.getSender().getId().equals(userId)) {
                throw new RuntimeException("You can only delete your own sent messages for everyone");
            }
            if (message.getCreatedAt().plusHours(1).isBefore(LocalDateTime.now())) {
                throw new RuntimeException("Cannot delete for everyone after 1 hour");
            }
            message.setDeleted(true);
            message.setDeletedBy(userId);
            message.setDeletedAt(LocalDateTime.now());
            message.setDeleteType("FOR_EVERYONE");
            message.setContent(encryption.encrypt(DELETED_MSG));


            reactionRepository.deleteByMessageId(req.getMessageId());

            messageRepository.save(message);

            MessageDeleteDTO dto = MessageDeleteDTO.builder()
                    .messageId(req.getMessageId())
                    .deletedBy(userId)
                    .otherUserId(otherUserId)
                    .deleteType("FOR_EVERYONE")
                    .build();

            messagingTemplate.convertAndSend("/queue/message-deleted/" + otherUserId, dto);
            messagingTemplate.convertAndSend("/queue/message-deleted/" + userId, dto);

            return dto;
        } else {
            message.setHiddenFor(userId);
            messageRepository.save(message);

            MessageDeleteDTO dto = MessageDeleteDTO.builder()
                    .messageId(req.getMessageId())
                    .deletedBy(userId)
                    .otherUserId(otherUserId)
                    .deleteType("FOR_ME")
                    .build();

            messagingTemplate.convertAndSend("/queue/message-deleted/" + userId, dto);

            return dto;
        }
    }

    public void broadcastTyping(Long senderId, Long receiverId, boolean typing) {
        User sender = userRepository.findById(senderId).orElse(null);
        if (sender == null) return;

        TypingDTO dto = TypingDTO.builder()
                .userId(senderId)
                .userName(sender.getName())
                .typing(typing)
                .build();

        messagingTemplate.convertAndSend("/queue/typing/" + receiverId, dto);
    }

    private MessageDTO mapToDTO(Message m) {
        String content;
        if (Boolean.TRUE.equals(m.getDeleted())) {
            content = DELETED_MSG;
        } else {
            content = decryptSafe(m.getContent());
        }


        List<ReactionDTO> reactionDTOs = Collections.emptyList();
        if (m.getReactions() != null && !m.getReactions().isEmpty()) {
            reactionDTOs = m.getReactions().stream()
                    .map(this::mapReactionToDTO)
                    .collect(Collectors.toList());
        }

        MessageDTO.MessageDTOBuilder builder = MessageDTO.builder()
                .id(m.getId())
                .senderId(m.getSender().getId())
                .senderName(m.getSender().getName())
                .senderProfilePic(m.getSender().getProfilePicUrl())
                .receiverId(m.getReceiver().getId())
                .content(content)
                .readStatus(Boolean.TRUE.equals(m.getReadStatus()))
                .readAt(m.getReadAt())
                .deleted(Boolean.TRUE.equals(m.getDeleted()))
                .deletedBy(m.getDeletedBy())
                .deleteType(m.getDeleteType())
                .hiddenFor(m.getHiddenFor())
                .reactions(reactionDTOs)
                .createdAt(m.getCreatedAt());

        if (m.getReplyTo() != null) {
            Message reply = m.getReplyTo();
            builder.replyToId(reply.getId());
            if (Boolean.TRUE.equals(reply.getDeleted())) {
                builder.replyToContent(DELETED_MSG);
            } else {
                builder.replyToContent(decryptSafe(reply.getContent()));
            }
            builder.replyToSenderName(reply.getSender().getName());
        }

        return builder.build();
    }

    private String decryptSafe(String content) {
        if (content == null) return "";
        try {
            if (encryption.isEncrypted(content)) {
                return encryption.decrypt(content);
            }
            return content;
        } catch (Exception e) {
            return content;
        }
    }
}