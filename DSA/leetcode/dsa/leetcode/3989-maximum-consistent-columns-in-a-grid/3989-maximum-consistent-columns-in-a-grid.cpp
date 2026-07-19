class Solution {
public:
    int maxConsistentColumns(vector<vector<int>>& grid, int limit) {
        int n = size(grid[0]);
        vector<int> dp(n,1);
        auto isValid = [](const auto &grid, const auto &limit, const auto &i, const auto &j) { 
            for(int r = 0; r < grid.size(); r++){
                if(abs(grid[r][i] - grid[r][j]) > limit) return false;
            }
            return true;
        };
        for(int i = 0 ; i < n ; i++){
            for(int j = 0 ; j < i ; j++){
                if(dp[j]+1 > dp[i])
                    if(isValid(grid,limit,i,j)){
                        dp[i] = dp[j]+1;
                    }
            }
        }
        return *max_element(dp.begin(),dp.end());
    }
};