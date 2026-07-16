package vn.phusa.repo

import org.springframework.data.jpa.repository.JpaRepository
import vn.phusa.domain.Source

interface SourceRepository : JpaRepository<Source, Long> {
    fun findBySlug(slug: String): Source?
}
