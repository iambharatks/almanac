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
        int res = choose(nums,0,size(nums)-1);
        return res >= 0;
    }
};