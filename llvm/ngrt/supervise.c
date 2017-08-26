#include <err.h>
#include <errno.h>
#include <limits.h>	/* for SSIZE_MAX */
#include <signal.h>	/* for pthread_sigmask(3) */
#include <stdio.h>
#include <stdint.h>	/* for intmax_t */
#include <stdlib.h>
#include <string.h>	/* for strdup(3), strlen(3) */
#include <sys/param.h>	/* for MIN */
#include <sys/stat.h>	/* for lstat(2), fts(3) */
#include <sys/types.h>	/* for waitpid(2), fts(3) */
#include <fts.h>
#include <sys/wait.h>
#include <unistd.h>	/* for pathconf(2), readlink(2) */

#include "init.h"
#include "interpose.h"
#include "nbcompat.h"	/* for __arraycount() */
#include "supervise.h"

static const char *tmproot = "/tmp";
static const char *self_exe_pathname = "/proc/self/exe";
static const char *trace_var = "RVP_TRACE_FILE";

char *
get_binary_path(char *argv0)
{
	char *linkname;
	ssize_t namesize = 4;
	const long path_max = pathconf(self_exe_pathname, _PC_NAME_MAX);

	if (path_max == -1) {
		err(EXIT_FAILURE, "RV-Predict/C could not find out the "
		    "maximum filename length");
	}

	for (namesize = MIN(4, path_max);
	     namesize <= path_max;
	     namesize = (SSIZE_MAX - namesize >= namesize)
	         ? (namesize + namesize)
		 : SSIZE_MAX) {
		linkname = malloc(namesize + 1);
		if (linkname == NULL) {
			errx(EXIT_FAILURE, "RV-Predict/C could not allocate "
			    "memory for the link name in %s",
			    self_exe_pathname); 
		}

		const ssize_t nread = readlink(self_exe_pathname, linkname,
		    namesize + 1);

		if (nread == -1) {
			if (errno == ENOENT)
				return argv0;
			err(EXIT_FAILURE,
			    "RV-Predict/C could not read the link name from %s",
			    self_exe_pathname);
		}

		if (nread > namesize) {
			free(linkname);
			continue;
		}
		linkname[nread] = '\0';
		return linkname;
	}
	errx(EXIT_FAILURE, "RV-Predict/C could not allocate a "
	    "buffer big enough to read the link at %s", self_exe_pathname);
}

static void
restore_signals(sigset_t *omask)
{
	if ((errno = real_pthread_sigmask(SIG_SETMASK, omask, NULL)) != 0) {
		err(EXIT_FAILURE,
		    "RV-Predict/C could not restore the signal mask");
	}
}

static void
block_signals(sigset_t *omask)
{
	static const int signum[] = {SIGHUP, SIGINT, SIGQUIT, SIGPIPE, SIGALRM,
	    SIGTERM};
	int i;
	sigset_t s;

	for (i = 0; i < __arraycount(signum); i++) {
		if (sigaddset(&s, signum[i]) == -1)
			goto errexit;
	}

	if ((errno = real_pthread_sigmask(SIG_BLOCK, &s, omask)) == 0)
		return;
errexit:
	err(EXIT_FAILURE, "RV-Predict/C could not block signals");
}

static void
cleanup(char *tmpdir)
{
	FTS *tree;
	FTSENT *entry;

	char *paths[] = {tmpdir, NULL};

	if ((tree = fts_open(paths, FTS_PHYSICAL, NULL)) == NULL) {
		err(EXIT_FAILURE, "RV-Predict/C could not clean up its "
		    "temporary directory at %s", tmpdir);
	}

	while ((entry = fts_read(tree)) != NULL) {
		switch (entry->fts_info) {
		case FTS_F:
		case FTS_SL:
		case FTS_SLNONE:
		case FTS_DEFAULT:
			if (unlink(entry->fts_path) == -1 && errno != ENOENT) {
				warn("RV-Predict/C encountered "
				    "an error at non-directory path %s",
				    entry->fts_path);
			}
			break;
		case FTS_DNR:
		case FTS_ERR:
		case FTS_NS:
			warnx("RV-Predict/C encountered an error "
			    "at path %s: %s", entry->fts_path,
			    strerror(entry->fts_errno));
			break;
		case FTS_DP:
			if (rmdir(entry->fts_path) == -1) {
				err(EXIT_FAILURE, "RV-Predict/C could not "
				    "remove a temporary directory");
			}
			break;
		default:
			continue;
		}
	}
}

void
__rvpredict_main_entry(int argc, char **argv)
{
	int status;
	pid_t pid;
	const long path_max = pathconf(self_exe_pathname, _PC_NAME_MAX);
	char *binbase, *tmpdir, *tracename;
	int nwritten;
	sigset_t omask;

	/* __rvpredict_init() set rvp_trace_only to true if the
	 * environment variable RVP_TRACE_ONLY is set to "yes".
	 * Resume running the main routine immediately if it is "yes"
	 * so that we drop a trace file into the working directory.
	 */
	if (rvp_trace_only)
		return;

	char *const binpath = get_binary_path((argc > 0) ? argv[0] : NULL);

	if (binpath == NULL) {
		errx(EXIT_FAILURE, "RV-Predict/C could not find a path to the "
		    "executable binary that it was running");
	}

	if ((tmpdir = malloc(path_max + 1)) == NULL ||
	    (tracename = malloc(path_max + 1)) == NULL) {
		errx(EXIT_FAILURE,
		    "RV-Predict/C could not allocate two buffers of %ld bytes",
		    path_max + 1);
	}

	if ((binbase = strdup(binpath)) == NULL) {
		errx(EXIT_FAILURE,
		    "RV-Predict/C could not allocate %ld bytes storage",
		    strlen(binpath) + 1);
	}

	nwritten = snprintf(tmpdir, path_max + 1, "%s/rvprt-%s.XXXXXX",
	    tmproot, basename(binbase));

	if (nwritten < 0 || nwritten >= path_max + 1) {
		errx(EXIT_FAILURE,
		    "RV-Predict/C could not create a temporary directory name");
	}

	if (mkdtemp(tmpdir) == NULL) {
		err(EXIT_FAILURE,
		    "RV-Predict/C could not create a temporary directory");
	}

	nwritten = snprintf(tracename, path_max + 1, "%s/rvpredict.trace",
	    tmpdir);

	if (nwritten < 0 || nwritten >= path_max + 1) {
		errx(EXIT_FAILURE,
		    "RV-Predict/C could not create a trace filename");
	}

	if (setenv(trace_var, tracename, 1) != 0) {
		err(EXIT_FAILURE,
		    "RV-Predict/C could not export a trace filename to "
		    "the environment");
	}

	// TBD Avoid using some arbitrary file in the analysis.
	// Check binpath for an RV-Predict/C runtime symbol, and see if
	// its address matches the address of our own copy?

	if ((pid = fork()) == -1) {
		err(EXIT_FAILURE,
		    "RV-Predict/C could not fork a supervisor process");
	}

	block_signals(&omask);

	/* the child process runs the program: return to its main routine */
	if (pid == 0) {
		restore_signals(&omask);
		return;
	}

	/* wait for the child to finish */
	while (waitpid(pid, &status, 0) == -1) {
		if (errno == EINTR)
			continue;
		err(EXIT_FAILURE,
		    "RV-Predict/C failed unexpectedly "
		    "while it waited for the instrumented program");
	}

	if ((pid = fork()) == -1) {
		err(EXIT_FAILURE,
		    "RV-Predict/C could not fork an analysis process");
	}

	if (pid == 0) {
		char *const args[] = {"rvpa", binpath, NULL};

		if (chdir(tmpdir) == -1) {
			err(EXIT_FAILURE, "RV-Predict/C could not change to "
			    "its temporary directory");
		}
		if (execvp("rvpa", args) == -1) {
			err(EXIT_FAILURE,
			    "RV-Predict/C could not start an analysis process");
		}
		// unreachable
	}

	/* wait for the child to finish */
	while (waitpid(pid, NULL, 0) == -1) {
		if (errno == EINTR)
			continue;
		err(EXIT_FAILURE,
		    "RV-Predict/C failed unexpectedly "
		    "while it waited for the analysis process to finish");
	}

	cleanup(tmpdir);

	if (WIFSIGNALED(status))
		exit(125);	// following xargs(1) here. :-)
	if (WIFEXITED(status))
		exit(WEXITSTATUS(status));
	exit(EXIT_SUCCESS);
}
