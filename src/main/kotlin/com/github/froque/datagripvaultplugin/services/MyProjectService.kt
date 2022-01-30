package com.github.froque.datagripvaultplugin.services

import com.intellij.openapi.project.Project
import com.github.froque.datagripvaultplugin.MyBundle

class MyProjectService(project: Project) {

    init {
        println(MyBundle.message("projectService", project.name))
    }
}
