class Solution {
public:
    vector<vector<int>> dp;
    int choose(vector<int> &arr, int l, int r){
        if(l>r) return 0;
        if(dp[l][r] != -1) return dp[l][r];
        return dp[l][r] = max(arr[l]-choose(arr,l+1,r),arr[r]-choose(arr,l,r-1));
    }
    bool predictTheWinner(vector<int>& nums) {
        // if 0 
        //     res =  max choose(i+1,n,0)+arr[0]  choose(i,n-1,0) +arr[n-1]
        // if 1 
        //     res = min choose(i+1,n,0)-arr[0]  choose(i,n-1,0) -arr[n-1] 
        int n = size(nums);
        dp.assign(n,vector<int>(n,-1));
        // int res = choose(nums,0,size(nums)-1);
        for(int i = 0 ; i < n ; i++) dp[i][i] = nums[i];
        for(int l = n-1; l >=0 ; l--){
            for(int r = l+1 ; r < n ; r++){
                dp[l][r] = max(nums[l]-dp[l+1][r],nums[r]-dp[l][r-1]);
            }
        }
        return dp[0][n-1] >= 0;
    }
};