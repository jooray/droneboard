// SPDX-License-Identifier: GPL-3.0-only
// Lightweight persistent helper thread pool (1-2 worker threads) for parallelizing
// multi-language neural LM evaluation and multi-trie decoding without per-call thread spawn.
#pragma once

#include <atomic>
#include <condition_variable>
#include <cstddef>
#include <deque>
#include <exception>
#include <mutex>
#include <thread>
#include <vector>

namespace droneswipe {

class HelperThreadPool {
public:
    explicit HelperThreadPool(size_t num_workers = 2) : max_workers_(num_workers) {}

    ~HelperThreadPool() {
        {
            std::lock_guard<std::mutex> lock(q_mtx_);
            stop_ = true;
        }
        q_cv_.notify_all();
        for (auto& t : workers_) {
            if (t.joinable()) t.join();
        }
    }

    HelperThreadPool(const HelperThreadPool&) = delete;
    HelperThreadPool& operator=(const HelperThreadPool&) = delete;

    // Runs fn(0), fn(1), ..., fn(n - 1) across the calling thread + helper workers.
    // Reentrant-safe: if invoked from inside a worker thread, executes serially to avoid deadlock.
    template <class Fn>
    void parallel_for(size_t n, Fn&& fn) {
        if (n <= 1 || in_worker_flag() || max_workers_ == 0) {
            for (size_t i = 0; i < n; ++i) fn(i);
            return;
        }
        ensure_started();

        struct State {
            size_t n;
            Fn* fn;
            std::atomic<size_t> next{0};
            size_t pending_helpers = 0;
            std::mutex done_mtx;
            std::condition_variable done_cv;
            std::mutex err_mtx;
            std::exception_ptr err;

            void run_loop() {
                while (true) {
                    const size_t i = next.fetch_add(1, std::memory_order_relaxed);
                    if (i >= n) break;
                    try {
                        (*fn)(i);
                    } catch (...) {
                        std::lock_guard<std::mutex> el(err_mtx);
                        if (!err) err = std::current_exception();
                    }
                }
            }
        };

        auto helper_trampoline = [](void* arg) {
            auto* st = static_cast<State*>(arg);
            st->run_loop();
            std::lock_guard<std::mutex> dl(st->done_mtx);
            if (--st->pending_helpers == 0) {
                st->done_cv.notify_one();
            }
        };

        State state;
        state.n = n;
        state.fn = &fn;
        const size_t helpers = (n - 1 < workers_.size()) ? (n - 1) : workers_.size();
        state.pending_helpers = helpers;

        {
            std::lock_guard<std::mutex> lock(q_mtx_);
            for (size_t h = 0; h < helpers; ++h) {
                queue_.push_back(Task{helper_trampoline, &state});
            }
        }
        if (helpers == 1) q_cv_.notify_one();
        else q_cv_.notify_all();

        // Calling thread participates in the same work-stealing loop.
        state.run_loop();

        // Wait under done_mtx so workers have unlocked done_mtx before `state` is destroyed.
        {
            std::unique_lock<std::mutex> dl(state.done_mtx);
            state.done_cv.wait(dl, [&] { return state.pending_helpers == 0; });
        }

        if (state.err) std::rethrow_exception(state.err);
    }

private:
    struct Task {
        void (*fn)(void*);
        void* arg;
    };

    static bool& in_worker_flag() {
        static thread_local bool flag = false;
        return flag;
    }

    void ensure_started() {
        std::call_once(start_once_, [this] {
            workers_.reserve(max_workers_);
            for (size_t i = 0; i < max_workers_; ++i) {
                workers_.emplace_back([this] { worker_main(); });
            }
        });
    }

    void worker_main() {
        in_worker_flag() = true;
        while (true) {
            Task task{nullptr, nullptr};
            {
                std::unique_lock<std::mutex> lock(q_mtx_);
                q_cv_.wait(lock, [this] { return stop_ || !queue_.empty(); });
                if (stop_ && queue_.empty()) return;
                task = queue_.front();
                queue_.pop_front();
            }
            if (task.fn) task.fn(task.arg);
        }
    }

    size_t max_workers_;
    std::once_flag start_once_;
    std::vector<std::thread> workers_;
    std::mutex q_mtx_;
    std::condition_variable q_cv_;
    std::deque<Task> queue_;
    bool stop_ = false;
};

inline HelperThreadPool& helper_pool() {
    static HelperThreadPool pool(2);
    return pool;
}

}  // namespace droneswipe
