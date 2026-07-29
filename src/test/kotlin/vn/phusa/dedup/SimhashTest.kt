package vn.phusa.dedup

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The property under test is the one that separates simhash from SHA-256: SIMILAR
 * INPUTS MUST PRODUCE SIMILAR OUTPUTS. A cryptographic hash is built to destroy that
 * relationship, so every assertion here would fail for `content_hash` — which is the
 * whole reason layer 3 exists alongside layer 2.
 */
class SimhashTest {

    /**
     * Realistic Vietnamese prose at a REALISTIC LENGTH — ~700 words, matching the
     * corpus (extracted bodies run 600-3300 words).
     *
     * Length matters to what these tests assert. Simhash distance tracks the FRACTION
     * of a document that changed, so the same one-sentence edit moves ~4 bits on a
     * 500-character document and 0 bits on a 3,400-character one. Measured across
     * lengths: appending one sentence gives 4 bits at 504 chars, 1 bit at 1,701, and 0
     * bits from 3,399 upward. Testing the threshold against a toy-length document would
     * be testing the wrong regime.
     */
    private val article = (1..6).joinToString(" ") { """
        Chào anh em, hôm nay chúng ta sẽ tìm hiểu về cách xây dựng một hệ thống thu thập
        tin tức bằng Kotlin và Spring Boot. Hệ thống này sẽ đọc dữ liệu từ nhiều nguồn RSS
        khác nhau, sau đó lưu trữ vào cơ sở dữ liệu PostgreSQL. Việc khử trùng lặp là một
        bài toán thú vị, bởi vì cùng một bài viết có thể xuất hiện ở nhiều nơi với những
        địa chỉ khác nhau. Chúng ta sẽ dùng nhiều kỹ thuật khác nhau, mỗi kỹ thuật bắt được
        những trường hợp mà kỹ thuật trước bỏ sót. Đầu tiên là chuẩn hóa địa chỉ, sau đó là
        băm nội dung chính xác, rồi đến simhash cho các bản gần giống nhau. Mỗi tầng khử
        trùng lặp giải quyết một loại trùng lặp mà tầng trước đó không thể phát hiện ra
        được, và đó chính là lý do chúng ta cần nhiều tầng chứ không phải chỉ một. Đoạn
        văn thứ $it trong bài viết này nói về cách hệ thống xử lý dữ liệu thu thập được
        từ các nguồn tin tức công nghệ khác nhau ở Việt Nam và trên thế giới.
    """.trimIndent().replace("\n", " ") }

    @Test
    fun `identical text gives an identical fingerprint`() {
        assertEquals(Simhash.of(article), Simhash.of(article))
    }

    /**
     * The load-bearing property. A publisher's correction, an added intro sentence, a
     * different photo caption — the same article to a reader, and a completely different
     * value to SHA-256.
     */
    @Test
    fun `a small edit moves only a few bits`() {
        val edited = "$article Cập nhật: bài viết đã được sửa lỗi chính tả vào hôm qua."
        val d = Simhash.distance(assertNotNull(Simhash.of(article)), assertNotNull(Simhash.of(edited)))
        assertTrue(d <= DuplicateResolver.MAX_DISTANCE, "small edit moved $d bits, threshold is ${DuplicateResolver.MAX_DISTANCE}")
    }

    @Test
    fun `unrelated text is far away`() {
        val other = (1..6).joinToString(" ") { """
            Hôm nay thời tiết Hà Nội khá đẹp, nhiệt độ dao động từ hai mươi đến hai mươi
            lăm độ. Người dân đổ ra đường đi chơi rất đông, các quán cà phê chật kín khách.
            Dự báo cuối tuần trời sẽ chuyển lạnh, nhiệt độ giảm sâu vào ban đêm, bà con cần
            giữ ấm cẩn thận và hạn chế ra ngoài khi trời tối muộn nếu không thật cần thiết.
            Đoạn văn số $it mô tả tình hình giao thông tại các tuyến phố trung tâm thủ đô
            trong dịp cuối tuần, khi lượng phương tiện tăng cao hơn hẳn ngày thường.
        """.trimIndent().replace("\n", " ") }
        val d = Simhash.distance(assertNotNull(Simhash.of(article)), assertNotNull(Simhash.of(other)))
        assertTrue(d > 12, "unrelated documents only $d bits apart — the fingerprint is not discriminating")
    }

    /**
     * Shingles, not bag-of-words. Reordering sentences keeps every WORD but changes the
     * PHRASING, and a word-level simhash would call this the same document.
     */
    @Test
    fun `word order matters`() {
        val shuffled = article.split(". ").reversed().joinToString(". ")
        val d = Simhash.distance(assertNotNull(Simhash.of(article)), assertNotNull(Simhash.of(shuffled)))
        assertTrue(d > 0, "reordering produced an identical fingerprint — this is a bag of words")
    }

    /**
     * Vietnamese diacritics must be significant. "ma", "mà", "má", "mã" and "mả" are
     * five different words; unaccenting would make different sentences hash alike, which
     * is why the tokenizer lowercases but never strips accents.
     */
    @Test
    fun `diacritics change the fingerprint`() {
        val stripped = article
            .replace("ệ", "e").replace("ề", "e").replace("ế", "e").replace("ữ", "u")
            .replace("ộ", "o").replace("ố", "o").replace("ả", "a").replace("ạ", "a")
        val d = Simhash.distance(assertNotNull(Simhash.of(article)), assertNotNull(Simhash.of(stripped)))
        assertTrue(d > 0, "accents were ignored — 'ma' and 'mã' would be one word")
    }

    @Test
    fun `case is ignored`() {
        assertEquals(Simhash.of(article), Simhash.of(article.uppercase()))
    }

    // ---- refusing to answer ---------------------------------------------------

    /** Same philosophy as content_hash's floor: too little text is not evidence. */
    @Test
    fun `short or empty text yields null`() {
        assertNull(Simhash.of(""))
        assertNull(Simhash.of("Chào anh em"))
        assertNull(Simhash.of("x".repeat(600)), "600 identical chars is one token, not a document")
    }

    @Test
    fun `distance is symmetric and zero against itself`() {
        val a = assertNotNull(Simhash.of(article))
        val b = assertNotNull(Simhash.of(article + " Một câu bổ sung ở cuối bài viết này."))
        assertEquals(0, Simhash.distance(a, a))
        assertEquals(Simhash.distance(a, b), Simhash.distance(b, a))
    }

    /**
     * Fingerprints use the full 64-bit range, so negative Longs are normal and must not
     * break distance — the same reason the SQL casts to `bit(64)` before `bit_count`.
     */
    @Test
    fun `distance handles the sign bit`() {
        assertEquals(64, Simhash.distance(-1L, 0L))
        assertEquals(1, Simhash.distance(Long.MIN_VALUE, 0L))
    }
}
