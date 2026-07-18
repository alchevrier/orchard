package com.orchard.backend.conversation

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ConversationAuthorityTest {
    @Test
    fun `conversation ledger replays ordered messages objectives commands and correlation`() {
        val directory = createTempDirectory("orchard-conversation-")
        val store = FileConversationStore(directory)
        val conversation = newConversation(Conversation(1, "Orchard development", "HUMAN", NOW, ""))
        val message = newConversationMessage(ConversationMessage(
            1, 1, 1, "message-client-0001", MESSAGE_ROLE_USER, "Fix the durable conversation boundary.",
            actor = "HUMAN", createdAt = NOW, hash = "",
        ))
        val objective = newConversationObjective(ConversationObjectiveRevision(
            1, 1, 1, 1, "Durable conversation", "Persist and replay one objective.", priority = 80,
            state = OBJECTIVE_READY, sourceMessageId = message.messageId, sourceMessageHash = message.hash,
            actor = "HUMAN", createdAt = NOW, hash = "",
        ))
        val command = newConversationCommand(ConversationCommandProposal(
            1, 1, objective.objectiveId, message.messageId, message.hash, "START_WORKFLOW", "{\"workItemId\":4}",
            true, NOW, "",
        ))
        val admission = newConversationAdmission(ConversationCommandAdmission(1, 1, command.hash, "HUMAN", NOW, ""))
        val dispatched = newConversationExecution(ConversationCommandExecution(1, 1, command.hash, COMMAND_DISPATCHED, recordedAt = NOW, hash = ""))
        val correlated = newConversationExecution(ConversationCommandExecution(
            2, 1, command.hash, COMMAND_CORRELATED, "WORKFLOW_RUN", "7", "a".repeat(64), "b".repeat(40),
            recordedAt = NOW, hash = "",
        ))
        val activity = newConversationActivity(ConversationActivity(
            1, 1, objective.objectiveId, ACTIVITY_INFO, "Conversation inference recorded.",
            "MODEL_BINDING", "binding", "c".repeat(64), NOW, "",
            ConversationModelProvenance(
                "bounded-conversation-conductor-v1", 1, "c".repeat(64), "binding", "OLLAMA", "phi3:mini",
                configurationHash = "d".repeat(64), promptHash = "e".repeat(64), outputHash = "f".repeat(64),
                promptTokens = 120, completionTokens = 40, latencyMillis = 25,
                inputBudgetTokens = 48_000, outputBudgetTokens = 4_000, resourceDecision = "ADMITTED",
            ),
        ))

        store.appendConversation(conversation)
        store.appendMessage(message)
        store.appendObjective(objective)
        store.appendCommand(command)
        store.appendAdmission(admission)
        store.appendExecution(dispatched)
        store.appendExecution(correlated)
        store.appendActivity(activity)

        val replayed = FileConversationStore(directory).events()
        assertEquals((1L..8L).toList(), replayed.map { it.eventId })
        assertEquals(correlated, replayed[6].execution)
        assertEquals(activity.modelProvenance, replayed.last().activity?.modelProvenance)
        assertFailsWith<IllegalArgumentException> { store.appendMessage(message.copy(messageId = 2, sequence = 2)) }
    }

    @Test
    fun `objective dependency cycles fail closed`() {
        val store = TransientConversationStore()
        val conversation = newConversation(Conversation(1, "Parallel work", "HUMAN", NOW, ""))
        val firstMessage = newConversationMessage(ConversationMessage(
            1, 1, 1, "message-client-0001", MESSAGE_ROLE_USER, "Create two objectives.", actor = "HUMAN", createdAt = NOW, hash = "",
        ))
        store.appendConversation(conversation)
        store.appendMessage(firstMessage)
        val first = newConversationObjective(ConversationObjectiveRevision(
            1, 1, 1, title = "First", outcome = "First outcome", state = OBJECTIVE_READY,
            sourceMessageId = 1, sourceMessageHash = firstMessage.hash, actor = "HUMAN", createdAt = NOW, hash = "",
        ))
        store.appendObjective(first)
        val second = newConversationObjective(ConversationObjectiveRevision(
            2, 1, 1, title = "Second", outcome = "Second outcome", dependencyObjectiveIds = listOf(1), state = OBJECTIVE_READY,
            sourceMessageId = 1, sourceMessageHash = firstMessage.hash, actor = "HUMAN", createdAt = NOW, hash = "",
        ))
        store.appendObjective(second)
        val cycle = newConversationObjective(first.copy(
            revision = 2, dependencyObjectiveIds = listOf(2), previousHash = first.hash, hash = "",
        ))

        assertFailsWith<IllegalArgumentException> { store.appendObjective(cycle) }
    }

    private companion object {
        const val NOW = "2026-07-18T12:00:00Z"
    }
}