class Solution {
public:
    int choose(vector<int> &arr, int l, int r, bool chance){
        if(l>r) return 0;
        if(chance){
            return min(choose(arr,l+1,r,!chance)-arr[l],choose(arr,l,r-1,!chance)-arr[r]);
        }
        return max(choose(arr,l+1,r,!chance)+arr[l],choose(arr,l,r-1,!chance)+arr[r]);
    }
    bool predictTheWinner(vector<int>& nums) {
        // if 0 
        //     res =  max choose(i+1,n,0)+arr[0]  choose(i,n-1,0) +arr[n-1]
        // if 1 
        //     res = min choose(i+1,n,0)-arr[0]  choose(i,n-1,0) -arr[n-1] 
        int res = choose(nums,0,size(nums)-1,false);
        return res >= 0;
    }
};