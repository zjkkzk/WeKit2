package dev.ujhhgtg.wekit.agent.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import dev.ujhhgtg.wekit.agent.tool.ProviderKind
import dev.ujhhgtg.wekit.agent.tool.ToolMode
import java.time.Instant

// ---------------------------------------------------------------------------
// Conversation domain (§10)
// ---------------------------------------------------------------------------

@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey val id: String,
    val title: String,
    val systemPromptId: String?,
    val workspaceId: String?,
    val modelId: String,
    val createdAt: Instant,
    val updatedAt: Instant,
)

enum class MessageRole { USER, ASSISTANT, TOOL, SYSTEM }

@Entity(
    tableName = "messages",
    indices = [Index("sessionId")],
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val role: MessageRole,
    val content: String,
    val createdAt: Instant,
)

enum class ApprovalStatus { AUTO_ALLOWED, USER_APPROVED, USER_REJECTED, AI_APPROVED, AI_REJECTED }

@Entity(
    tableName = "tool_calls",
    indices = [Index("messageId")],
)
data class ToolCallEntity(
    @PrimaryKey val id: String,
    val messageId: String,
    val provider: String,
    val toolName: String,
    val argumentsJson: String,
    val resultJson: String?,
    val approvalStatus: ApprovalStatus,
    val approvalReason: String?,
    val executedAt: Instant?,
)

// ---------------------------------------------------------------------------
// Tool providers & permissions (§10)
// ---------------------------------------------------------------------------

enum class McpTransport { STREAMABLE_HTTP, SSE }

@Entity(tableName = "providers")
data class ProviderEntity(
    @PrimaryKey val id: String,
    val kind: ProviderKind,
    val name: String,
    val transport: McpTransport?,
    val endpointUrl: String?,
    val headersJson: String?,
    val enabled: Boolean,
)

@Entity(tableName = "tool_permissions", primaryKeys = ["providerId", "toolName"])
data class ToolPermissionEntity(
    val providerId: String,
    val toolName: String,
    val mode: ToolMode,
)
