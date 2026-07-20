class Solution {
public:
    vector<vector<int>> shiftGrid(vector<vector<int>>& grid, int k) {
        vector<vector<int>> res(grid);
        int n = size(grid);
        int m = size(grid[0]);
        for(int i = 0 ; i < n ; i++){
            for(int j = 0 ; j < m ; j++){
                int newPos = (i*m+j+k)%(m*n);
                res[newPos/m][newPos%m] = grid[i][j];
            }
        }
        return res;
    }
};