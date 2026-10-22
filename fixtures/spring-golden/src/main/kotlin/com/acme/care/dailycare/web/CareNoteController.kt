package com.acme.care.dailycare.web

import com.acme.care.dailycare.notes.CareNoteService
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

data class CareNoteDto(
    val noteId: UUID,
    val residentId: UUID,
    val category: String,
    val text: String,
    val author: String,
    val recordedAt: Instant,
)

data class CreateCareNoteRequest(
    val residentId: UUID,
    @field:NotBlank val category: String,
    @field:NotBlank val text: String,
)

@RestController
@RequestMapping("/api/dailycare/notes")
class CareNoteController(private val careNoteService: CareNoteService) {

    @GetMapping("/{residentId}")
    fun listNotes(
        @PathVariable residentId: UUID,
        @RequestParam(required = false) since: Instant?,
    ): List<CareNoteDto> = careNoteService.notesFor(residentId, since)

    @PostMapping
    fun createNote(@RequestBody @Valid request: CreateCareNoteRequest): ResponseEntity<CareNoteDto> =
        ResponseEntity.status(HttpStatus.CREATED).body(careNoteService.record(request))

    @PostMapping("/{noteId}/attachments", consumes = ["multipart/form-data"])
    fun attach(@PathVariable noteId: UUID): ResponseEntity<Unit> = ResponseEntity.accepted().build()
}
