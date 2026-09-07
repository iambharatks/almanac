class Solution {
    int dr[4] = {0,0,-1,1};
    int dc[4] = {-1,1,0,0};
    int N,M;
    vector<vector<vector<vector<int>>>> dp;
public:
    int rec(int n, int m, int dir, int turn,vector<vector<int>>& grid){
        if(n < 0 || m < 0 || n >= N || m >= M) return INT_MAX;
        if(n ==0 && m == 0) return grid[n][m];
        int res = INT_MAX;
        if(dp[n][m][turn][dir] != -1) return dp[n][m][turn][dir];
        for(int i = 0 ; i < 4 ; i++){
            int r = n+dr[i];
            int c = m+dc[i]; 
            if(r >= 0 && c >= 0 && r < N && c < M){
                if(dir != i && turn == 0) continue;
                if(dir == i)
                    res = min(res,rec(r,c,i,turn,grid));
                else res = min(res,rec(r,c,i,turn-1,grid));
            }
        }
        return dp[n][m][turn][dir] = (res == INT_MAX?INT_MAX:res+grid[n][m]);
    }
    int minCost(vector<vector<int>>& grid, int k) {
        N = size(grid);
        M = size(grid[0]);
        if(N == 1 && M == 1) return grid[N-1][M-1];
        dp.assign(N+1,vector<vector<vector<int>>>(M+1,vector<vector<int>>(k+1,vector<int>(4,-1))));
        int res = min(rec(N-2,M-1,2,k,grid),rec(N-1,M-2,0,k,grid));
        if(res == INT_MAX) return -1;
        return res+grid[N-1][M-1];
    }
};