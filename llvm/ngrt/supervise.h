#ifndef _RVP_SUPERVISE_H_
#define _RVP_SUPERVISE_H_

#include <stdbool.h>

extern bool rvp_trace_only;
void rvp_supervision_start(void);
char *get_binary_path(void);

#endif /* _RVP_SUPERVISE_H_ */
