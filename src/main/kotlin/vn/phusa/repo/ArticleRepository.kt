package vn.phusa.repo

import org.springframework.data.jpa.repository.JpaRepository
import vn.phusa.domain.Article

interface ArticleRepository : JpaRepository<Article, Long>
