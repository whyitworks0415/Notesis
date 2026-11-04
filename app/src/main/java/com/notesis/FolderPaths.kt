package com.notesis

/*
 * Folders inside folders. A note's folder is a path, its folders' names
 * joined by '/', so a note filed before folders could hold folders keeps
 * the folder it was in: a name without a '/' is a folder at the top level.
 */

/** [raw] as a folder path: each name trimmed, empty names dropped; null when nothing is left. */
internal fun normalizeFolderPath(raw: String): String? =
    raw.split('/').map { it.trim() }.filter { it.isNotEmpty() }.joinToString("/").ifEmpty { null }

/** The folder [path] is in; "" for one at the top level. */
internal fun folderParent(path: String): String = path.substringBeforeLast('/', "")

/** The folder's own name, the last of its path. */
internal fun folderLeaf(path: String): String = path.substringAfterLast('/')

/** [child] put in [parent], or at the top level when [parent] is blank. */
internal fun folderChild(parent: String, child: String): String =
    if (parent.isBlank()) child else "$parent/$child"

/** Whether [path] is [folder] itself or somewhere inside it. */
internal fun isInFolder(path: String, folder: String): Boolean =
    folder.isNotEmpty() && (path == folder || path.startsWith("$folder/"))

/** Every path in [paths] with the folders that hold it, so a folder with only folders in it still shows. */
internal fun withAncestorFolders(paths: Collection<String>): List<String> {
    val all = LinkedHashSet<String>()
    for (path in paths) {
        var at = normalizeFolderPath(path) ?: continue
        while (at.isNotEmpty() && all.add(at)) at = folderParent(at)
    }
    return all.sorted()
}

/** The folders directly in [parent] ("" for the top level), out of [all]. */
internal fun folderChildren(all: Collection<String>, parent: String): List<String> =
    all.filter { it.isNotEmpty() && folderParent(it) == parent }.sortedBy { folderLeaf(it).lowercase() }

/** Where [path] goes when the folder [from] becomes [to]: itself and what is inside it move along. */
internal fun movedFolderPath(path: String, from: String, to: String): String = when {
    path == from -> to
    path.startsWith("$from/") -> to + path.substring(from.length)
    else -> path
}
