package vn.phusa

import org.springframework.boot.fromApplication
import org.springframework.boot.with


fun main(args: Array<String>) {
	fromApplication<PhusaApplication>().with(TestcontainersConfiguration::class).run(*args)
}
