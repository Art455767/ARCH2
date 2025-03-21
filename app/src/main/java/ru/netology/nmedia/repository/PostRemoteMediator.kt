package ru.netology.nmedia.repository

import androidx.paging.ExperimentalPagingApi
import androidx.paging.LoadType
import androidx.paging.PagingState
import androidx.paging.RemoteMediator
import androidx.room.withTransaction
import ru.netology.nmedia.api.ApiService
import ru.netology.nmedia.dao.PostDao
import ru.netology.nmedia.dao.PostRemoteKeyDao
import ru.netology.nmedia.db.AppDb
import ru.netology.nmedia.dto.Post
import ru.netology.nmedia.entity.PostEntity
import ru.netology.nmedia.entity.PostRemoteKeyEntity
import ru.netology.nmedia.entity.toEntity
import ru.netology.nmedia.error.ApiError

@OptIn(ExperimentalPagingApi::class)
class PostRemoteMediator(
    private val service: ApiService,
    private val db: AppDb,
    private val postDao: PostDao,
    private val postRemoteKeyDao: PostRemoteKeyDao,
) : RemoteMediator<Int, PostEntity>() {

    override suspend fun load(
        loadType: LoadType,
        state: PagingState<Int, PostEntity>
    ): MediatorResult {
        return try {
            val response = when (loadType) {
                LoadType.REFRESH -> {
                    if (postDao.isEmpty()) {
                        service.getLatest(state.config.initialLoadSize)
                    } else {
                        val maxId = postRemoteKeyDao.max() ?: return MediatorResult.Success(endOfPaginationReached = true)
                        service.getAfter(maxId, state.config.initialLoadSize)
                    }
                }

                LoadType.PREPEND -> {
                    return MediatorResult.Success(endOfPaginationReached = true)
                }

                LoadType.APPEND -> {
                    val minId = postRemoteKeyDao.min() ?: return MediatorResult.Success(endOfPaginationReached = false)
                    service.getBefore(minId, state.config.pageSize)
                }
            }

            if (!response.isSuccessful) {
                throw ApiError(response.code(), response.message())
            }
            val body = response.body() ?: throw ApiError(response.code(), response.message())

            db.withTransaction {
                postDao.insert(body.toEntity())
                handleDatabaseInsert(loadType, body)
            }
            MediatorResult.Success(endOfPaginationReached = body.isEmpty())
        } catch (e: Exception) {
            MediatorResult.Error(e)
        }
    }

    private suspend fun handleDatabaseInsert(loadType: LoadType, body: List<Post>) {
        when (loadType) {
            LoadType.REFRESH -> {
                updateRemoteKeysForRefresh(body)
            }

            LoadType.APPEND -> {
                updateRemoteKeysForAppend(body)
            }

            else -> {}
        }
    }

    private suspend fun updateRemoteKeysForRefresh(body: List<Post>) {
        if (postDao.isEmpty()) {
            postRemoteKeyDao.insert(
                listOf(
                    PostRemoteKeyEntity(
                        type = PostRemoteKeyEntity.KeyType.AFTER,
                        id = body.first().id,
                    ),
                    PostRemoteKeyEntity(
                        type = PostRemoteKeyEntity.KeyType.BEFORE,
                        id = body.last().id,
                    ),
                )
            )
        } else {
            postRemoteKeyDao.insert(
                PostRemoteKeyEntity(
                    type = PostRemoteKeyEntity.KeyType.AFTER,
                    id = body.first().id,
                )
            )
        }
    }

    private suspend fun updateRemoteKeysForAppend(body: List<Post>) {
        postRemoteKeyDao.insert(
            PostRemoteKeyEntity(
                type = PostRemoteKeyEntity.KeyType.BEFORE,
                id = body.last().id,
            )
        )
    }
}