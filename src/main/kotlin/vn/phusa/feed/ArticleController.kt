package vn.phusa.feed

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * The public feed. Keyset pagination only — there is no `?page=` parameter by design:
 * page numbers are wrong under concurrent inserts (a new article at the top shifts
 * every offset), and `OFFSET n` gets linearly slower with depth. Clients follow
 * `nextCursor`.
 */
@RestController
@RequestMapping("/api/articles")
class ArticleController(
    private val service: ArticleFeedService,
) {
    @GetMapping
    fun feed(
        @RequestParam(required = false) cursor: String?,
        @RequestParam(required = false) limit: Int?,
    ): ArticleFeedResponse = service.feed(cursor, limit)
}
